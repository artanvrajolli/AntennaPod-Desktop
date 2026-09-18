# AntennaPod Desktop app icon

The chosen icon is **gradient mesh** — a soft orange/pink/blue/violet mesh behind a
white play badge, drawn on a rounded tile.

![the icon](app-icon-preview.png)

## Files

| Path | Used for |
|------|----------|
| `app.ico` | `jpackage` — the `.exe` icon, Start-menu shortcut and installer |
| `../app/src/main/resources/icons/app-icon-{16,24,32,48,64,128,256}.png` | window/taskbar icon (JavaFX picks the right size) and the tray icon |
| `../app/src/main/resources/icons/app-icon.png` | 256 px copy for anything that wants a single file |

## Regenerating

```powershell
powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1
```

The script draws everything on a 512×512 virtual canvas and scales it down, so
editing the `$drawAppIcon` block (colours, shape, badge) updates every size and
the `.ico` in one run. PNG frame sizes are set by `$sizes`.
