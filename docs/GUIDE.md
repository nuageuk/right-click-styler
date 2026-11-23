# Settings guide

Most appearance settings are in the Right Click Styler panel in the RuneLite sidebar. Sidebar visibility is controlled from Right Click Styler's normal RuneLite configuration page.

Classic is selected by default, and every built-in theme uses the RuneScape Bold font by default. The screenshots below show one configured theme; their colour values are examples, not factory defaults.

## Getting started

1. Click the Right Click Styler button in the RuneLite sidebar.
2. Select Dark, Midnight, Light, Glass or Classic from the theme list.
3. Click an orange section heading to expand it, then adjust the controls. Changes appear immediately in the preview and in game.
4. To keep changes made to a built-in theme, click **Save as…**, enter a name and create a custom theme.
5. Use **Save** when you later edit that custom theme.

Some settings belong to the selected theme and some apply everywhere:

- **Colours**, **Style** and **Text** are part of a theme. Edits are live, but are only kept after you save the custom theme. Built-in edits must be kept with **Save as…**.
- **Action highlights**, **Targets**, **Combat levels** and **Advanced** apply to every theme. These controls save automatically. A global colour is committed when its colour picker closes.

Unsaved theme edits are temporary. They are lost if you discard them, close RuneLite, or switch RuneLite configuration profiles.

## Preview and theme management

![The live preview and theme selector with Save, Save as, Revert and More controls](images/settings/01-preview.png)

### Preview

The preview at the top shows a sample menu drawn with the current theme and settings. Move the mouse over it to see the hover style. When you are logged in, it uses your combat level for the sample combat-level colours.

### Themes

The five built-in themes are protected presets, marked with a lock icon. You can edit their appearance temporarily, but you cannot overwrite, rename or delete them. Your custom themes appear after the built-in themes in the list.

- **Theme list**: selects and activates a theme. Switching themes asks before discarding unsaved edits.
- **Save**: overwrites the selected custom theme with its current edits. It is unavailable for built-in themes and when nothing has changed.
- **Save as…**: saves the current visible appearance as a new custom theme and selects it. Use this to keep edits made to a built-in theme.
- **Revert**: discards unsaved edits and restores the selected theme's saved appearance. It is unavailable when nothing has changed. It asks for confirmation first: choose **Revert changes** to discard the edits, or **Cancel** to keep editing.
- **More**:
  - **Rename**: renames the selected custom theme.
  - **Duplicate**: makes and selects a copy of the selected theme. Unsaved edits must be discarded first, so the copy uses the selected theme's saved appearance.
  - **Delete**: asks for confirmation, then deletes the selected custom theme and returns to Classic. Any unsaved edits to that theme are also lost.
  - **Export**: copies the current visible appearance to the clipboard as one line of text. It includes unsaved edits but does not save them. The More button briefly shows **Copied!**.
  - **Import**: adds and selects a custom theme from exported text. The input is pre-filled when the clipboard contains text. Unsaved edits must be discarded first.

Importing an existing theme creates a separate custom theme with a new internal identity. Duplicate names are allowed. Invalid or incomplete text is rejected, and no more than 50 custom themes can be saved in one RuneLite profile.

Theme names are limited to 40 characters. Empty names are not accepted.

### Sharing a theme

1. Select the theme and click **More > Export**.
2. Send the copied line of text to another player.
3. They click **More > Import**, paste the complete text and press OK.

## Colours

![The Colours section with background, border, header, text, hover and accent swatches](images/settings/02-colours.png)

Click a colour to open the colour picker. These colours are part of the theme and change the working appearance immediately.

- **Background**: the main fill of the menu.
- **Border**: the outline around the menu when Border is enabled in Style.
- **Header**: the fill behind "Choose Option".
- **Header text**: the "Choose Option" text.
- **Option text**: the action text, such as "Attack", when the game has not supplied its own colour and no action highlight applies.
- **Hover**: the fill used by the Fill and Bar hover styles.
- **Accent**: the bar in Bar, the outline in Outline, and the hovered action text in Text.

Hover and action highlights are separate. Hover describes the row under the pointer. Action highlights colour matching action text wherever it appears in a menu drawn by Right Click Styler.

Classic uses a brown background, black border and header, brown header text and hover fill, white option text and a yellow accent.

## Style

![The Style section showing separate background and hover opacity controls](images/settings/03-style.png)

- **Corner radius**: controls how rounded the menu corners are, from 0 for square corners to 8.
- **Background opacity**: controls the background and header fill from 0% to 100%.
- **Hover opacity**: independently controls the Hover fill used by Fill and Bar, from 0% to 100%. The two translucent fills can overlap. Neither control fades text, borders, accent bars, accent outlines or shadows.
- **Hover style**:
  - **Fill**: fills the hovered row with Hover.
  - **Bar**: uses a Hover fill with an Accent bar on the left.
  - **Outline**: draws an Accent outline around the row.
  - **Text**: changes the hovered action text to Accent.
  - **None**: adds no hover effect.
- **Border**: draws the Border colour around the menu.
- **Drop shadow**: draws a soft shadow beneath the menu.
- **Header**: shows "Choose Option". Hiding it removes the header area while keeping rows aligned with the game's click areas.

Classic defaults to square corners, 100% background and hover opacity, Text hover, Border on, Drop shadow off and Header on.

With a translucent background, the game world remains visible through the menu while text and outlines stay solid:

![A semi-transparent styled Drakan's medallion menu over the game world](images/transparency.png)

## Text

![The Text section with font, font size and text shadow settings](images/settings/04-text.png)

- **Font**: Regular, Small and Bold are RuneScape's fonts. Sans and Serif are standard scalable fonts. Sans and Serif display runs of extra spaces as a single space.
- **Font size**: ranges from 11 to 13. It only applies to Sans and Serif, so the control is disabled for the RuneScape fonts.
- **Text shadow**: draws a dark shadow behind text.

Classic uses Bold with Text shadow enabled. RuneScape fonts have a fixed size.

## Action highlights

![The Action highlights section with enabled and bold switches and individual action rules](images/settings/05-action-highlights.png)

Action highlights change the action text only. They do not change target names, combat levels, menu entries or click behaviour.

- **Enabled**: turns all action highlight rules on or off. It is on by default.
- **Bold**: uses bold text for highlighted actions. It is on by default.
- **Rules**: each row contains a complete action name, a colour and a remove button.
- **+ Add rule**: adds a rule. Up to 50 rules can be saved.

Rules match the complete displayed action name after the game's formatting is removed. Matching ignores capital letters and surrounding spaces, but is not a partial match. For example, `Attack` matches `attack`, but not `Attack player`.

Press Enter or leave the action field to save a changed name. Closing the colour picker saves a changed colour. Blank action names are not saved. If the same action appears more than once in the rules, the last matching rule wins; rule order otherwise has no effect.

The default rules highlight Attack in red and Walk here and Examine in grey. With the Text hover style, Accent temporarily takes precedence over an action's highlight colour while that row is hovered. A highlighted action remains bold if Bold is enabled.

## Targets

![The Targets section with Game and Custom modes and target-type colour controls](images/settings/06-targets.png)

The target is the thing an action is used on, such as "Guard" in "Attack Guard".

- **Mode > Game**: keeps the target colours supplied by the game. This is the default.
- **Mode > Custom**: uses the NPC, Player, Item, Object and Other colours below. These colour controls are disabled in Game mode.
- **NPC**, **Player**, **Item**, **Object**: colour those target types.
- **Other**: colours targets that are not recognised as one of those types.

The default custom colours are yellow for NPCs, white for players, orange for items, cyan for objects and white for Other.

Choose **Reset Target Colours to Defaults** in the Targets section to restore those five colour values. It is available in Custom mode and asks for confirmation first: choose **Reset colours** to continue, or **Cancel** to keep your current colours. Nothing is asked, and nothing changes, when the colours already match their defaults. It leaves the selected mode, combat-level settings, action rules and saved themes alone. In Custom mode, colours explicitly added within a target by the game or another plugin are preserved where they differ from the game's usual category colour; results can vary with the source formatting.

## Combat levels

![The Combat levels section with Game and Custom modes and lower, same and higher level colours](images/settings/07-combat-levels.png)

These settings control the `(level-N)` text after NPC and player names independently of the rest of the target.

- **Mode > Game**: keeps the level colour supplied by the game. This is the default.
- **Mode > Custom**: compares the displayed level with your combat level and uses Lower level, Same level or Higher level. These colour controls are disabled outside Custom mode.
- **Mode > Hide**: removes level text while preserving the game's native menu width.

The default custom colours are green for lower levels, yellow for the same level and red for higher levels.

Choose **Reset Combat Colours to Defaults** to restore the Lower level, Same level and Higher level colours. Like the target reset, it is available in Custom mode, asks for confirmation first and does nothing when the colours already match their defaults. It leaves the selected mode, target settings, action rules and saved themes alone.

Combat-level mode takes precedence over target mode for the level portion. For example, Custom target colours can colour an NPC name while Custom combat colours independently colour its level. Action highlights affect only the action before the target.

## Advanced

![The Advanced section with text positioning, Show hit rows and Reset all settings](images/settings/08-advanced.png)

You will not normally need these controls.

- **Text offset X**: sets the space between the menu edge and the text, from 0 to 20. The default is 3, and the same space is reserved on the right.
- **Baseline nudge**: moves text up or down from -10 to 10. The default is 0.
- **Show hit rows**: outlines each native row click area for alignment checking. This is a diagnostic control, off by default; leave it off for ordinary play.
- **Reset all settings**: after confirmation, restores Action highlights, Targets, Combat levels and Advanced to their defaults, selects Classic and discards unsaved theme edits. Saved custom themes are preserved.

The menu keeps the game's native width and click areas. When text is wider than its available row or header space, the renderer fits it within that space. Narrow menus or large offsets may make the text appear compressed.

## RuneLite profiles

Right Click Styler settings, the active theme and saved custom themes belong to the active RuneLite configuration profile. Switching profiles reloads that profile's independent settings and theme list.

If a theme disappears after a profile switch, switch back to the profile where it was saved or export it there and import it into the new profile. Switching profiles discards unsaved theme edits without a confirmation prompt.

## RuneLite plugin settings

![Right Click Styler plugin settings](images/pluginhubsettings.png)

*Sidebar visibility and RuneLite's full configuration reset.*

Use **Show sidebar button** to hide or restore the Right Click Styler sidebar button. It is enabled by default. If the button is missing, open Right Click Styler in RuneLite's normal configuration panel and enable this setting. **Back** returns to RuneLite's plugin list.

The **Reset** button shown here restores Right Click Styler's configuration defaults, selects Classic and restores the sidebar button. Saved custom themes are preserved.

The sidebar's **Advanced > Reset all settings** also restores visual defaults and Classic while preserving saved custom themes, but it does not change sidebar visibility.

## Compatibility and limitations

- Interface Styles can remain enabled if **High detail menu** is off and **Menu alpha** is 255. If either setting conflicts, the sidebar shows a warning and a chat message appears once per Right Click Styler plugin session when the conflict is detected while logged in.
- Scrollable parent menus and child submenus can be styled when the native framebuffer draw and menu layout can be verified. Ambiguous or unsupported frames keep the normal game appearance.
- Right Click Styler preserves the game's menu width, row positions and click regions. It changes appearance only.
- A failed final submenu draw uses native rendering for the remainder of that menu opening; a new opening retries. A failed ordinary-menu render uses native rendering until the plugin is restarted. In rare cases, a menu change after native drawing has been consumed can leave one frame without a visible menu.
- Ground-item icons inserted into context menus by other plugins are not currently reproduced by the styled renderer. Menu geometry and selection remain native.
- Exceptionally large menus may reduce FPS while open. Normal-sized menus showed no measurable FPS difference in live Windows testing.
- Sidebar controls such as the **More** chevron and the rule-removal cross are drawn rather than taken from Unicode font glyphs. This has been verified on Windows; confirmation on macOS is pending.

The submenu path samples the game's own draw to identify visible child regions, then restores the sampled framebuffer before drawing the style. It does not create menu actions or change the game's selection handling.

### Menus in game

This Menu Entry Swapper example shows a styled parent and child menu together:

![Styled parent and child Drakan's medallion menus with Menu Entry Swapper's Swap left-click action](images/styled-submenu.png)

On a large Grand Exchange player menu, the game still supplies the scroll position and click areas:

<img src="images/scrollable-menu.png" alt="A large styled Grand Exchange player menu with native scrolling and a child menu" width="720">

## Troubleshooting

### A menu uses the normal game appearance

A submenu or scrollable menu can temporarily use the normal appearance when its native draw cannot be verified. If an ordinary menu keeps using the normal appearance after a drawing failure, disable and re-enable Right Click Styler to start a new plugin session.

### The normal menu appears beneath a translucent theme

Open Interface Styles, turn off **High detail menu** and set **Menu alpha** to 255. Interface Styles can stay enabled with those compatible settings.

### The sidebar button disappeared

Open Right Click Styler in RuneLite's normal configuration panel and enable **Show sidebar button**.

### A theme import fails

Copy and paste the complete exported line. Imports are rejected when the text is invalid or too long, or when the current RuneLite profile already has 50 custom themes.

### Theme edits were not saved

Use **Save** for a custom theme or **Save as…** for edits to a built-in theme. Export includes unsaved edits but does not save them. Closing RuneLite or switching RuneLite profiles discards unsaved edits.

### A theme is missing after switching profiles

Custom themes are profile-specific. Switch back to the original RuneLite profile, or export the theme from that profile and import it into the new one.

### Resetting the plugin

Both **Advanced > Reset all settings** in the sidebar and RuneLite's normal **Reset** restore defaults and Classic while preserving saved custom themes. RuneLite's Reset also restores the sidebar button.
