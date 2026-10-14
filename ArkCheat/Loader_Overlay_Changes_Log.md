# Loader Source (BEAST / CROZN) — Overlay APK Changes Log

Ye MD batata hai **loader APK** (BEAST/CROZN base) mein kya-kya add kiya taaki
floating overlay + Aurix-style menu + TCP control kaam kare.

---

## Base
- BEAST loader (`com.beastcrown`) aur CROZN loader (`com.crozn`) — dono par same changes
- Ye loader game ko virtual space se launch karta hai + `IMGUI.zip` lib inject karta hai
- Overlay us injected lib ko TCP se control karta hai

---

## Changes

### 1. Naya file: `OverlayService.java`
Floating overlay service — Aurix-style menu (orange theme).

**Features:**
- **Floating "A" logo** (fire logo, bg removed) — draggable, tap se menu khule
- **Aurix layout:** left sidebar (logo + Visual/Aim/Misc tabs) + right content panel
- **Menu draggable** (title row se)
- **Orange theme** (logo se match — blue nahi, jaisा user ne bola)
- **Persistent state:** toggle on karke menu band/reopen karo to state yaad rehta hai
  (pehle off dikhta tha — ab fix)

**Tabs (Aurix jaisा):**
- **Visual:** Line, Skeleton, Health, Distance, Name (ESP checkboxes)
- **Aim:** Bullet Track + Aimbot (exclusive — ek on to dusra off)
- **Misc:** iPad View, Hide ESP

**Har toggle → TCP:** `127.0.0.1:28900` pe command bheje (`esp=1`, `aimbot=1` etc.)

### 2. `MainActivity.java`
- Overlay imports add (`Settings`, `Build`, `Uri`)
- `launchGame()` ke baad `startOverlayMenu()` call
- `startOverlayMenu()` method:
  - Overlay permission check → nahi hai to request (`ACTION_MANAGE_OVERLAY_PERMISSION`)
  - Hai to `startService(OverlayService)`

### 3. `AndroidManifest.xml`
- `OverlayService` register kiya (`<service android:name=".OverlayService" ...>`)
- Permissions (SYSTEM_ALERT_WINDOW, INTERNET, FOREGROUND_SERVICE) — pehle se the

### 4. Lib link change
- `BEAST.zip` / `CROZN.zip` → **`IMGUI.zip`**
- Dono jagah: `utils/Downtwo.java` + `jni/main.cpp`
- Ab loader Ark ki IMGUI lib (TCP-enabled) download + inject karta hai

### 5. Logo
- Fire "A" logo (`float_logo.png`) — background remove karke drawable me
- Floating icon + sidebar dono me use hota hai

---

## Flow (poora)

```
Login success → Launch dabao
   ↓
Virtual space → BGMI install/launch + IMGUI.zip lib inject
   ↓
startOverlayMenu() → overlay permission check → OverlayService start
   ↓
Floating "A" logo screen pe aata hai (draggable)
   ↓
Logo tap → Aurix-style menu (orange)
   ↓
Toggle (e.g. ESP) → TCP "esp=1" → 127.0.0.1:28900
   ↓
Injected Ark lib → Cheat::Esp::* = true → ESP draw
```

---

## Config

| | BEAST | CROZN |
|---|---|---|
| Package | com.beastcrown | com.crozn |
| NDK | 24 (unchanged) | 24 (unchanged) |
| Lib link | IMGUI.zip | IMGUI.zip |
| Menu title | BEAST | CROZN |
| Theme | Orange (fire-A) | Orange (fire-A) |
| TCP port | 28900 | 28900 |

---

## Changed files

| File | Change |
|------|--------|
| `java/.../OverlayService.java` | **NEW** — Aurix menu + TCP client |
| `java/.../MainActivity.java` | startOverlayMenu + overlay perm |
| `AndroidManifest.xml` | OverlayService registered |
| `utils/Downtwo.java` | lib link → IMGUI.zip |
| `jni/main.cpp` | lib link → IMGUI.zip |
| `res/drawable/float_logo.png` | fire-A logo (bg removed) |

---

## Test checklist

1. Loader build → install
2. `IMGUI.zip` (Ark TCP-native lib) `KingCheats` release pe daal
3. Login → Launch → game khule (koi dialog nahi)
4. Overlay "A" logo aaye → drag karke test karo
5. Logo tap → Aurix menu (orange) khule
6. Visual tab → ESP toggle on → in-game ESP aaye + watermark
7. Menu band karke reopen → toggle state yaad rahe (on hi dikhe)
8. Aim tab → Aimbot on → BulletTrack auto-off ho
9. Misc → Hide ESP on → sirf hidden ESP (normal band ho)

Sab sahi to connection + UI dono complete!
