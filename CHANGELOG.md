# Changelog

## [1.1.2] — 2026-11-23

### Fixed

- The sidebar's **More** chevron and rule-removal cross are now drawn with Java2D instead of Unicode symbols, so they no longer depend on the system font. The temporary **Copied!** state and existing button actions are unchanged. ([#2](https://github.com/nuageuk/right-click-styler/issues/2))
- In Custom target mode, combined item interactions such as **Use Knife → Logs** now give both names their Custom Item colour. This covers inventory, equipped and ground items, and mixed targets such as an item used on a player. ([#6](https://github.com/nuageuk/right-click-styler/issues/6))
- Intentional colours from other plugins, such as Loot Filters, are still preserved in combined targets. Game target mode keeps the game's own colours.

### Compatibility

- Existing themes, exports, configuration keys and RuneLite profiles remain supported.

### Known limitations

- The drawn sidebar icons have been verified on Windows. Confirmation on macOS is pending.
- Other known limitations from v1.1.1 remain, except the combined-target colouring fixed above.

## [1.1.1] — 2026-11-22

### Added

- A **Reset Combat Colours to Defaults** control. It restores the lower, same and higher level colours while preserving the selected mode.
- Confirmation dialogs for **Revert** and both colour resets, preventing accidental loss of custom settings. No confirmation appears when there is nothing to discard or reset.
- Optional theme-sharing integration for compatible overlays such as Improved Right-Clicks, using RuneLite plugin messages. See [Plugin messages](PLUGIN_MESSAGES.md).

### Fixed

- Ground-item icons from compatible plugins now render in styled menus and submenus, including correct refreshes when their sprites are replaced.
- Custom target colours now apply correctly to ground items modified by Loot Filters, while preserving intentional third-party colours.

### Compatibility

- Existing themes, exports, configuration keys and RuneLite profiles remain supported.
- Right Click Styler works independently; integration with other plugins is optional.

### Known limitations

- Other known limitations from v1.1.0 remain unless addressed above.
- In Custom target mode, the second item in a combined target, such as **Use Knife → Logs**, retains the game's colour.

### Thanks

Special thanks to [@wukuna](https://github.com/wukuna) for contributing the ground-item icon improvements and theme-sharing integration in [PR #5](https://github.com/nuageuk/right-click-styler/pull/5), and for helping improve compatibility between our plugins!
## [1.1.0] — 2026-11-20

### Added

- Styling for parent and nested child submenus, and for large scrollable menus when their native draw can be verified.
- Separate background and hover opacity controls, each with a full 0–100% range.
- A dedicated **Reset Target Colours to Defaults** control.

### Improved

- Preservation of intentional third-party menu-entry colours in Custom target mode, including tested Loot Filters and Identificator interactions.
- Text fitting and clipping across fonts and long entries while retaining native menu bounds, rows and click regions.
- Handling of overlapping submenus and Menu Entry Swapper submenu interactions.
- Native fallback when a submenu frame cannot be verified, with a fresh styling attempt on the next opening after a failed draw.
- Reuse of styled submenu images when their rendering inputs match.
- Settings clarity, including the appearance of disabled controls and built-in theme lock indicators that work across fonts.
- README screenshots and the settings guide.

### Fixed

- Item target colours in submenu headers and copied inventory submenu entries.
- Menu header alignment and text fitting.
- Submenu arrows are always right-aligned, and rows whose submenu opens to the left keep the same text alignment as other rows.
- Uneven gaps between option, target and coloured text when a RuneScape-font row is narrowed to fit the native menu width.
- Custom Item target colours for worn items in the Equipment tab, including actions in their submenus.

### Compatibility

- Existing saved custom themes, theme exports, configuration keys and RuneLite profiles remain supported.
- Older themes without `hoverOpacity` use their saved background opacity for hover effects.
- Independent opacity values persist through theme saving, export and import.

### Known limitations

- Exceptionally large menus may reduce FPS while open.
- Some third-party visuals, including ground-item icons, are not reproduced by the styled renderer.
- macOS symbol rendering is unconfirmed. The built-in theme lock, which macOS showed as a missing-character box, is now drawn as an icon, but this release has been tested only on Windows.
- Reported inconsistencies with Inventory Setups potion withdrawals, the Max cape and fairy rings have not been confirmed as fixed.
- Submenus use native styling when their draw cannot be verified.

## [1.0.0] — 2026-11-16

This is a retrospective version label for the original Plugin Hub release; it was not published with explicit version metadata.

### Added

- Five built-in themes: Dark, Midnight, Light, Glass and Classic.
- Custom theme creation, saving and management, including import and export.
- Custom fonts, colours, backgrounds, borders, shadows and hover styles.
- Action highlights, target colours and combat-level styling.
- A live settings preview and sidebar editor.
