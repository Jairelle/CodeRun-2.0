# CodeRun 2.0: Island Edition

A multiplayer island adventure where you answer coding questions to win.

- **Main Island:** your home base. Meet other players, chat, visit **MercGian's** shop and start a match at the dock gate.
- **Island 1 · Baldi's Trivia Arena:** race to the answer pads (databases), then test the boat you built.
- **Island 2 · Dream Lake:** answer by fishing. Hook the school of fish with the right answer (data structures).
- **Island 3 · The Lighthouse Run:** sail through the storm, pick the right answer gates and reach the lighthouse (OOP).

Spend the coins and orbs you win on boats, rods, upgrades, clothes, hats, celebrations and name-tag frames. Some items unlock only at higher levels.

## ⬇️ Download

**[Windows – CodeRun-2.0-Setup.exe](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-Setup.exe)**  ·  **[Linux – CodeRun-2.0-linux-x64.tar.gz](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-linux-x64.tar.gz)**

All versions: [Releases page](https://github.com/Jairelle/CodeRun-2.0/releases)

**You don't need to install anything else.** Java and the game are already inside the download.

---

## What you need

| | Windows | Linux |
|---|---|---|
| System | Windows 10 or 11, 64-bit | Any 64-bit Linux desktop (Ubuntu, Fedora, Arch, Mint…) |
| Download | [CodeRun-2.0-Setup.exe](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-Setup.exe) | [CodeRun-2.0-linux-x64.tar.gz](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-linux-x64.tar.gz) |
| Free space | about 600 MB | about 600 MB |
| Internet | required (login, saving and multiplayer) | required |

---

## Windows

### Install
1. Download **[CodeRun-2.0-Setup.exe](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-Setup.exe)**.
2. If a blue box says **"Windows protected your PC"**, click **More info**, then **Run anyway**.
   This appears because the game is new and not signed by a big publisher. It is safe.
3. Click **Next**. You can keep the suggested folder or pick another one.
4. Click **Install**, then **Finish**.
   No administrator password is needed.

### Play
- Double-click **CodeRun 2.0** on your desktop, or find it in the **Start menu**.
- The first time you press **Play**, Windows may ask whether CodeRun can use the network.
  Click **Allow access** so multiplayer works.

---

## Linux

### Install
1. Download **[CodeRun-2.0-linux-x64.tar.gz](https://github.com/Jairelle/CodeRun-2.0/releases/latest/download/CodeRun-2.0-linux-x64.tar.gz)**.
2. Extract it in a terminal:
   ```
   tar -xzf CodeRun-2.0-linux-x64.tar.gz
   ```
   You now have a folder named **`CodeRun`**. Keep everything inside it together.
3. *(Optional)* Add CodeRun to your applications menu: open the `CodeRun` folder and run
   **`install-shortcut.sh`**. In a terminal:
   ```
   ./CodeRun/install-shortcut.sh
   ```
   You can then open **CodeRun 2.0** from the menu like any other app.

### Play
- Open the `CodeRun` folder, then **`bin`**, then double-click **`CodeRun`**.
- If double-clicking does nothing, start it from a terminal:
  ```
  ./CodeRun/bin/CodeRun
  ```

---

## How to play

1. **Sign up** in the launcher with your email (a code is sent to verify it), then **log in** and press **Play**.
2. You arrive on the **Main Island**. Walk around, chat, sit on a bench, and shop at **MercGian's** stall.
3. Walk to the **gate at the end of the dock** and press **E** to vote for an island with the other players.
4. Play the island, see your results, and sail back to the Main Island with your rewards.

Press **H** at any time to open the help card for where you are.

### Controls

| Key | What it does |
|---|---|
| **W A S D** | Walk |
| **Shift** | Run |
| **Space** | Jump |
| **E** | Talk to MercGian, sit down / stand up, start a match |
| **V** | Switch first / third person |
| **T** | Type a chat message (Enter sends, Esc cancels) |
| **Hold Q** | Quick chat and emotes (on the Main Island: SHEEEESH, +1000 AURA, Mewing and more) |
| **H** | How to play |
| **Esc** | Menu: sound, controls and view settings, leave the match |

Each island adds its own keys, such as answering with **1–4**, steering with **A / D** or reeling in with a **click**. Its help card lists them.

---

## Updating

The launcher checks for new versions by itself. When one is out, a green **Update** button appears in its top-right corner: click it and the launcher installs the new version for you.

To update by hand instead:
- **Windows:** run the new `CodeRun-2.0-Setup.exe`. It replaces the old version automatically.
- **Linux:** delete the old `CodeRun` folder and extract the new download.

Your progress is saved online, so it's kept either way.

**Play with friends on the same version.** Players on different versions may not see each other on the Main Island.

---

## What's new in 2.0.6

- Everyone now connects to the same game server region, so players on different internet connections land on the same Main Island.
- When you sit on a bench, log or the dock, other players see you sitting too.
- A new Merchant area: MercGian's stall with carved wooden signs, lanterns and goods, your boat on a cradle and your rod on a rack.
- A new match gate at the end of the dock, with a swirling water portal.
- New help cards on every island, plus a help card for the Main Island (press **H**).
- A short victory tune plays on the results screen.
- A new key bar at the bottom of the screen and a new game icon.

---

## Problems?

| Problem | Try this |
|---|---|
| "Windows protected your PC" | Click **More info**, then **Run anyway** |
| Launcher says it can't reach the database | Check your internet connection and try again |
| No verification email | Look in Spam/Promotions; wait a minute and click **Send code** again |
| The player bar says **Offline** | Your progress can't be saved right now. Check your internet, then restart the launcher |
| Game window doesn't open after **Play** (Linux) | Run `./CodeRun/bin/CodeRun` in a terminal and send the developer the text it shows |
| Can't see other players | Make sure everyone has the **latest version** (use the **Update** button). Some school, office and public Wi-Fi networks block multiplayer: try a phone hotspot or a home connection |
