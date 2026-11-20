# Changelog

## [1.1.0] — Unreleased

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
- Menu header alignment and text fitting, and submenu arrow positioning.

### Compatibility

- Existing saved custom themes, theme exports, configuration keys and RuneLite profiles remain supported.
- Older themes without `hoverOpacity` use their saved background opacity for hover effects.
- Independent opacity values persist through theme saving, export and import.

### Known limitations

- Exceptionally large menus may reduce FPS while open.
- Some third-party visuals, including ground-item icons, are not reproduced by the styled renderer.
- The macOS Unicode rendering issue remains unresolved.
- Submenus use native styling when their draw cannot be verified.

## [1.0.0] — 2026-11-16

This is a retrospective version label for the original Plugin Hub release; it was not published with explicit version metadata.

### Added

- Five built-in themes: Dark, Midnight, Light, Glass and Classic.
- Custom theme creation, saving and management, including import and export.
- Custom fonts, colours, backgrounds, borders, shadows and hover styles.
- Action highlights, target colours and combat-level styling.
- A live settings preview and sidebar editor.
