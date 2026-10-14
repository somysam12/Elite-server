/*
 * VM Obfuscation Pass — LLVM 14 New Pass Manager plugin
 *
 * Trigger: annotate a function with __attribute__((annotate("vmobf")))
 *
 * What it does:
 *   1. Demotes all SSA registers to stack memory (reg2mem)
 *   2. Numbers each BasicBlock as a "VM opcode"
 *   3. Replaces the function body with a dispatch loop (switch-based VM)
 *   4. XOR-obfuscates all stored opcode values so static analysis sees
 *      an interpreter rather than the original control flow
 */

#include "llvm/ADT/SmallVector.h"
#include "llvm/IR/BasicBlock.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/Function.h"
#include "llvm/IR/IRBuilder.h"
#include "llvm/IR/Instructions.h"
#include "llvm/IR/PassManager.h"
#include "llvm/Passes/PassBuilder.h"
#include "llvm/Passes/PassPlugin.h"
#include "llvm/Support/raw_ostream.h"
#include "llvm/Transforms/Utils/BasicBlockUtils.h"
#include "llvm/Transforms/Utils/Local.h"

#include <map>
#include <random>
#include <vector>

using namespace llvm;

// ────────────────────────────── helpers ──────────────────────────────────────

static bool hasAnnotation(const Function &F, StringRef Ann) {
    const auto *GV = F.getParent()->getNamedGlobal("llvm.global.annotations");
    if (!GV) return false;
    const auto *A = dyn_cast<ConstantArray>(GV->getOperand(0));
    if (!A) return false;
    for (unsigned i = 0, n = A->getNumOperands(); i < n; ++i) {
        const auto *E = dyn_cast<ConstantStruct>(A->getOperand(i));
        if (!E || E->getNumOperands() < 2) continue;
        // operand 0 is the function, operand 1 is the annotation string global
        auto *FnOp = E->getOperand(0)->stripPointerCasts();
        if (FnOp != &F) continue;
        auto *StrGV = dyn_cast<GlobalVariable>(
            E->getOperand(1)->stripPointerCasts());
        if (!StrGV || !StrGV->hasInitializer()) continue;
        auto *Str = dyn_cast<ConstantDataArray>(StrGV->getInitializer());
        if (Str && Str->getAsCString().contains(Ann)) return true;
    }
    return false;
}

static uint32_t getRandU32() {
    static std::mt19937 rng(std::random_device{}());
    return rng();
}

// reg2mem: demote PHI nodes and non-alloca SSA values to stack memory.
// Returns false if the function is trivially empty or already mem-based.
static bool reg2mem(Function &F) {
    // Skip empty / declaration functions
    if (F.isDeclaration() || F.empty()) return false;

    // Entry block: place alloca point after existing allocas
    BasicBlock &Entry = F.getEntryBlock();
    Instruction *AllocaPoint = &*Entry.getFirstInsertionPt();

    bool Changed = false;

    // Demote PHI nodes first
    SmallVector<PHINode *, 16> Phis;
    for (auto &BB : F)
        for (auto &I : BB)
            if (auto *P = dyn_cast<PHINode>(&I))
                Phis.push_back(P);
    for (auto *P : Phis) {
        DemotePHIToStack(P, AllocaPoint);
        Changed = true;
    }

    // Demote all non-trivial SSA values
    SmallVector<Instruction *, 64> Insts;
    for (auto &BB : F)
        for (auto &I : BB) {
            if (isa<AllocaInst>(I) || I.isTerminator()) continue;
            if (I.getType()->isVoidTy()) continue;
            if (I.use_empty()) continue;
            Insts.push_back(&I);
        }
    for (auto *I : Insts) {
        DemoteRegToStack(*I, false, AllocaPoint);
        Changed = true;
    }

    return Changed;
}

// ─────────────────────────── main transformation ─────────────────────────────

static bool vmObfuscateFunction(Function &F) {
    if (F.isDeclaration() || F.empty()) return false;
    if (F.size() < 2) return false; // nothing to dispatch

    LLVMContext &Ctx = F.getContext();
    (void)F.getParent(); // module unused directly
    IntegerType *I32 = Type::getInt32Ty(Ctx);

    // ── Step 1: reg2mem ──────────────────────────────────────────────────────
    reg2mem(F);

    // After reg2mem the entry block may have changed; re-collect blocks
    // (exclude the entry block from the dispatch loop candidates — it runs
    // exactly once at the start).
    std::vector<BasicBlock *> Blocks;
    for (auto &BB : F)
        Blocks.push_back(&BB);

    if (Blocks.size() < 2) return false;

    // ── Step 2: assign opcode numbers ────────────────────────────────────────
    // XOR key to obfuscate stored opcode values
    const uint32_t XorKey = (getRandU32() & 0xFFFF) | 1u; // always odd

    std::map<BasicBlock *, uint32_t> OpMap;
    uint32_t NextOp = 0;
    for (auto *BB : Blocks) {
        // Landing pad blocks MUST only be reached via invoke unwind edges —
        // never via a regular branch. Exclude them from the dispatch table.
        if (BB->isLandingPad()) continue;
        uint32_t raw = NextOp++;
        OpMap[BB] = raw ^ XorKey; // stored value is XOR-obfuscated
    }

    // ── Step 3: create the VM scaffold ──────────────────────────────────────
    // Insert a new entry block (pre-dispatch init) before the original entry.
    BasicBlock *OrigEntry = &F.getEntryBlock();
    BasicBlock *DispatchBB =
        BasicBlock::Create(Ctx, "vm.dispatch", &F, OrigEntry);
    BasicBlock *ExitBB =
        BasicBlock::Create(Ctx, "vm.exit", &F);

    // Move all alloca instructions from the original entry to a new init block
    // that runs before the dispatch loop.
    BasicBlock *InitBB = BasicBlock::Create(Ctx, "vm.init", &F, DispatchBB);

    // Steal all allocas from OrigEntry into InitBB
    {
        SmallVector<Instruction *, 32> Allocas;
        for (auto &I : *OrigEntry)
            if (isa<AllocaInst>(&I)) Allocas.push_back(&I);
        for (auto *A : Allocas) {
            if (InitBB->getTerminator())
                A->moveBefore(InitBB->getTerminator());
            else
                A->moveBefore(*InitBB, InitBB->end());
        }
    }

    // PC alloca goes into InitBB
    IRBuilder<> B(Ctx);
    B.SetInsertPoint(InitBB);
    AllocaInst *PCAlloca = B.CreateAlloca(I32, nullptr, "vm.pc");
    // Store the obfuscated entry-block opcode
    B.CreateStore(ConstantInt::get(I32, OpMap[OrigEntry]), PCAlloca);
    B.CreateBr(DispatchBB);

    // ── Step 4: dispatch block ────────────────────────────────────────────────
    B.SetInsertPoint(DispatchBB);
    LoadInst *PcLoad   = B.CreateLoad(I32, PCAlloca, "vm.pc.load");
    // De-obfuscate: actual_op = stored_val ^ XorKey
    Value *PcDeobf     = B.CreateXor(PcLoad,
                                     ConstantInt::get(I32, XorKey), "vm.pc.deobf");
    SwitchInst *Sw     = B.CreateSwitch(PcDeobf, ExitBB,
                                        (unsigned)Blocks.size());

    // Map raw opcode → BasicBlock (raw op = OpMap[BB] ^ XorKey)
    std::map<uint32_t, BasicBlock *> RawToBlock;
    for (auto &[BB, stored] : OpMap)
        RawToBlock[stored ^ XorKey] = BB; // raw = stored ^ XorKey

    for (auto &[raw, BB] : RawToBlock)
        Sw->addCase(ConstantInt::get(I32, raw), BB);

    // ── Step 5: vm.exit ───────────────────────────────────────────────────────
    // The exit block is unreachable in normal flow; it's here as a default.
    B.SetInsertPoint(ExitBB);
    B.CreateUnreachable();

    // ── Step 6: redirect each BB's terminator through the dispatch ────────────
    // For each basic block, every BranchInst/SwitchInst that jumps to another
    // block inside the function is replaced with:
    //   store (obfuscated_target_opcode), vm.pc
    //   br vm.dispatch
    for (auto *BB : Blocks) {
        Instruction *Term = BB->getTerminator();
        if (!Term) continue;

        if (auto *BI = dyn_cast<BranchInst>(Term)) {
            if (BI->isUnconditional()) {
                BasicBlock *Succ = BI->getSuccessor(0);
                if (OpMap.count(Succ)) {
                    IRBuilder<> Bld(Term);
                    Bld.CreateStore(ConstantInt::get(I32, OpMap[Succ]), PCAlloca);
                    BranchInst::Create(DispatchBB, Term);
                    Term->eraseFromParent();
                }
            } else {
                // Conditional branch: evaluate condition, then store appropriate opcode
                BasicBlock *TrueBB  = BI->getSuccessor(0);
                BasicBlock *FalseBB = BI->getSuccessor(1);
                bool trueSelf  = OpMap.count(TrueBB)  > 0;
                bool falseSelf = OpMap.count(FalseBB) > 0;
                if (trueSelf || falseSelf) {
                    IRBuilder<> Bld(Term);
                    Value *Cond = BI->getCondition();
                    Value *TrueOp  = trueSelf  ? (Value *)ConstantInt::get(I32, OpMap[TrueBB])
                                               : (Value *)PoisonValue::get(I32);
                    Value *FalseOp = falseSelf ? (Value *)ConstantInt::get(I32, OpMap[FalseBB])
                                               : (Value *)PoisonValue::get(I32);
                    Value *Selected = Bld.CreateSelect(Cond, TrueOp, FalseOp, "vm.sel");
                    Bld.CreateStore(Selected, PCAlloca);
                    if (trueSelf && falseSelf) {
                        BranchInst::Create(DispatchBB, Term);
                        Term->eraseFromParent();
                    } else if (trueSelf) {
                        // False goes outside (function call or external BB)
                        // Keep original false edge, replace true path
                        Bld.CreateCondBr(Cond, DispatchBB, FalseBB);
                        Term->eraseFromParent();
                    } else {
                        Bld.CreateCondBr(Cond, TrueBB, DispatchBB);
                        Term->eraseFromParent();
                    }
                }
            }
        } else if (auto *SI = dyn_cast<SwitchInst>(Term)) {
            // For switches inside the function, route all internal successors
            // through the dispatch loop.
            SmallVector<std::pair<ConstantInt *, BasicBlock *>, 8> Cases;
            for (auto &C : SI->cases())
                Cases.push_back({C.getCaseValue(), C.getCaseSuccessor()});
            BasicBlock *Default = SI->getDefaultDest();

            bool anyInternal = OpMap.count(Default) > 0;
            for (auto &[V, Succ] : Cases)
                anyInternal |= (OpMap.count(Succ) > 0);

            if (anyInternal) {
                IRBuilder<> Bld(Term);
                // Replace with a load+select chain: complex; for simplicity
                // redirect internal successors through trampoline blocks.
                for (auto &[V, Succ] : Cases) {
                    if (!OpMap.count(Succ)) continue;
                    BasicBlock *Tramp =
                        BasicBlock::Create(Ctx, "vm.tramp", &F);
                    IRBuilder<> TB(Tramp);
                    TB.CreateStore(ConstantInt::get(I32, OpMap[Succ]), PCAlloca);
                    TB.CreateBr(DispatchBB);
                    SI->replaceUsesOfWith(Succ, Tramp);
                }
                if (OpMap.count(Default)) {
                    BasicBlock *Tramp =
                        BasicBlock::Create(Ctx, "vm.tramp.def", &F);
                    IRBuilder<> TB(Tramp);
                    TB.CreateStore(ConstantInt::get(I32, OpMap[Default]), PCAlloca);
                    TB.CreateBr(DispatchBB);
                    SI->setDefaultDest(Tramp);
                }
            }
        } else if (auto *II = dyn_cast<InvokeInst>(Term)) {
            // InvokeInst: has a normal dest (normal return) and an unwind dest
            // (landing pad). The unwind edge MUST remain direct — never route it
            // through dispatch. Only redirect the normal dest.
            BasicBlock *NormalDest = II->getNormalDest();
            if (OpMap.count(NormalDest)) {
                // Insert a trampoline for the normal dest
                BasicBlock *Tramp = BasicBlock::Create(Ctx, "vm.tramp.invoke", &F);
                IRBuilder<> TB(Tramp);
                TB.CreateStore(ConstantInt::get(I32, OpMap[NormalDest]), PCAlloca);
                TB.CreateBr(DispatchBB);
                II->setNormalDest(Tramp);
            }
            // Unwind dest (landing pad): leave untouched
        }
        // ReturnInst and other terminators: leave as-is
    }

    return true;
}

// ─────────────────────────── pass wrapper ────────────────────────────────────

struct VmObfPass : PassInfoMixin<VmObfPass> {
    PreservedAnalyses run(Function &F, FunctionAnalysisManager &) {
        if (!hasAnnotation(F, "vmobf")) return PreservedAnalyses::all();
        // Strip optnone/noinline so the dispatch loop can be optimized later
        F.removeFnAttr(Attribute::OptimizeNone);
        F.removeFnAttr(Attribute::NoInline);
        if (vmObfuscateFunction(F)) return PreservedAnalyses::none();
        return PreservedAnalyses::all();
    }
    // Must return true so the pass runs even on optnone-annotated functions
    static bool isRequired() { return true; }
};

// ─────────────────────────── plugin entry point ──────────────────────────────

extern "C" __attribute__((visibility("default"))) __attribute__((weak))
::llvm::PassPluginLibraryInfo llvmGetPassPluginInfo() {
    return {LLVM_PLUGIN_API_VERSION, "VmObf", LLVM_VERSION_STRING,
            [](PassBuilder &PB) {
                // Manual pass name for -fpass-plugin=... pipelines
                PB.registerPipelineParsingCallback(
                    [](StringRef Name, FunctionPassManager &FPM,
                       ArrayRef<PassBuilder::PipelineElement>) -> bool {
                        if (Name == "vm-obf") {
                            FPM.addPass(VmObfPass());
                            return true;
                        }
                        return false;
                    });
                // Auto-inject at the very end of the optimizer pipeline
                PB.registerOptimizerLastEPCallback(
                    [](ModulePassManager &MPM, OptimizationLevel) {
                        FunctionPassManager FPM;
                        FPM.addPass(VmObfPass());
                        MPM.addPass(
                            createModuleToFunctionPassAdaptor(std::move(FPM)));
                    });
            }};
}
