package com.rightclickstyler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Upgrade compatibility against data written by the released plugin (see
 * {@code resources/com/rightclickstyler/released/README.md}). None of these fixtures are produced
 * by the code under test.
 */
public class ReleasedUpgradeCompatibilityTest
{
	private static final String PREFIX = RightClickStylerConfig.GROUP + ".";
	private static final Gson GSON = new Gson();
	/** Every field the released StoredTheme wrote, in its declaration order. */
	private static final List<String> RELEASED_THEME_FIELDS = Arrays.asList(
		"id", "name", "bg", "border", "header", "headerText", "text", "hover", "accent", "radius",
		"opacity", "hoverStyle", "showBorder", "showShadow", "showHeader", "font", "fontSize", "textShadow");
	private static final String UNSAVED_EDIT = "(unsaved dark edit)";

	// ---- Stage 2: existing users upgrading ----

	@Test
	public void fixturesAreInTheReleasedFormat() throws IOException
	{
		for (String profile : new String[]{"main", "alt"})
		{
			JsonArray themes = releasedThemes(RuneLiteProfileStore.released(profile));
			assertTrue(themes.size() > 0);
			for (JsonElement element : themes)
			{
				assertEquals("released themes carry exactly the released fields",
					RELEASED_THEME_FIELDS, new ArrayList<>(element.getAsJsonObject().keySet()));
			}
		}
		for (String[] export : releasedExports())
		{
			assertEquals(RELEASED_THEME_FIELDS, new ArrayList<>(decodeExport(export[2]).keySet()));
		}
	}

	@Test
	public void upgradeLoadsEveryReleasedThemeUnchangedWithoutWriting() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		Map<String, String> before = store.snapshot("main");
		ThemeManager manager = store.themeManager();

		JsonArray released = releasedThemes(store);
		List<Theme> loaded = manager.userThemes();
		assertEquals(released.size(), loaded.size());
		for (int i = 0; i < released.size(); i++)
		{
			assertMatchesReleased(released.get(i).getAsJsonObject(), loaded.get(i));
		}
		assertEquals("Raid Night", loaded.get(0).name);
		assertEquals("Solid ✨ Ränge", loaded.get(2).name);
		assertEquals("Raid Night (alt)", loaded.get(3).name);

		String selected = before.get(PREFIX + RightClickStylerConfig.KEY_ACTIVE_THEME);
		assertEquals(loaded.get(0).id, selected);
		assertEquals(selected, manager.activeId());
		assertTrue(manager.effectiveTheme().sameStyle(loaded.get(0)));
		assertFalse("an upgrade must not look like an unsaved edit", manager.isEdited());

		assertEquals("loading never writes", Collections.emptyList(), store.writes());
		assertEquals(before, store.snapshot("main"));
	}

	@Test
	public void upgradeKeepsEveryReleasedGlobalSetting() throws IOException
	{
		RightClickStylerConfig config = RuneLiteProfileStore.released("main").config();
		assertTrue("unset in the fixture, so the default", config.showSidebarButton());
		assertTrue("unset in the fixture, so the default", config.highlightsEnabled());
		assertFalse(config.highlightsBold());
		assertEquals("Attack=#FF0000\nPickpocket=#00C8FF\nUse=#A0FF40", config.highlightRules());
		List<HighlightRules.Rule> rules = HighlightRules.parse(config.highlightRules());
		assertEquals(3, rules.size());
		assertEquals("Pickpocket", rules.get(1).option);
		assertEquals(new Color(0x00C8FF), rules.get(1).colour);
		assertEquals(TargetMode.CUSTOM, config.targetMode());
		assertEquals(new Color(0x00FF80), config.npcColour());
		assertEquals(new Color(0xFF66CC), config.playerColour());
		assertEquals(new Color(0x3300FF), config.itemColour());
		assertEquals(new Color(0xFFD700), config.objectColour());
		assertEquals(new Color(0xAAAAAA), config.otherColour());
		assertEquals(CombatLevelMode.CUSTOM, config.combatLevelMode());
		assertEquals(new Color(0x00AA00), config.lowerLevelColour());
		assertEquals(new Color(0xCCCC00), config.sameLevelColour());
		assertEquals(new Color(0xDD0000), config.higherLevelColour());
		assertEquals(5, config.textOffsetX());
		assertEquals(-1, config.baselineNudge());
		assertFalse(config.showHitRows());

		RightClickStylerConfig alt = RuneLiteProfileStore.released("alt").config();
		assertEquals(TargetMode.GAME, alt.targetMode());
		assertEquals(new Color(0x00FFFF), alt.itemColour());
		assertEquals(2, alt.textOffsetX());
		assertEquals("glass", alt.activeTheme());
	}

	/**
	 * RuneLite persists config items by key name and converts them by return type; renaming a key
	 * or changing a type or default would silently drop or change released settings.
	 */
	@Test
	public void configItemsKeepTheReleasedKeysTypesAndDefaults()
	{
		Map<String, Object> released = new LinkedHashMap<>();
		released.put("showSidebarButton", true);
		released.put("activeTheme", "classic");
		released.put("highlightsEnabled", true);
		released.put("highlightsBold", true);
		released.put("highlightRules", "Attack=#FF5C5C\nWalk here=#7D8590\nExamine=#7D8590");
		released.put("targetMode", TargetMode.GAME);
		released.put("npcColour", new Color(0xFFFF00));
		released.put("playerColour", new Color(0xFFFFFF));
		released.put("itemColour", new Color(0xFF9040));
		released.put("objectColour", new Color(0x00FFFF));
		released.put("otherColour", new Color(0xFFFFFF));
		released.put("combatLevelMode", CombatLevelMode.GAME);
		released.put("lowerLevelColour", new Color(0x00FF00));
		released.put("sameLevelColour", new Color(0xFFFF00));
		released.put("higherLevelColour", new Color(0xFF3030));
		released.put("textOffsetX", 3);
		released.put("baselineNudge", 0);
		released.put("showHitRows", false);

		RightClickStylerConfig defaults = RuneLiteProfileStore.empty("fresh").config();
		Map<String, Object> current = new LinkedHashMap<>();
		for (Method method : RightClickStylerConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				try
				{
					current.put(item.keyName(), method.invoke(defaults));
				}
				catch (ReflectiveOperationException e)
				{
					throw new AssertionError(e);
				}
				assertEquals(item.keyName(), released.get(item.keyName()) == null ? null
					: unboxed(released.get(item.keyName()).getClass()), unboxed(method.getReturnType()));
			}
		}
		assertEquals(new TreeMap<>(released), new TreeMap<>(current));
		assertFalse("saved themes must stay outside RuneLite's annotated reset",
			current.containsKey(RightClickStylerConfig.KEY_USER_THEMES));
	}

	@Test
	public void releasedThemesRenderWithTheReleasedFills() throws IOException
	{
		Map<String, String> expected = releasedPixels();
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main");
		ThemeManager manager = store.themeManager();
		int checked = 0;
		for (Theme theme : manager.userThemes())
		{
			assertEquals(theme.name, expected.get(theme.id), samples(theme));
			checked++;
		}
		for (Theme theme : BuiltInThemes.ALL)
		{
			assertEquals(theme.id, expected.get(theme.id), samples(theme));
			checked++;
		}
		ThemeManager imported = RuneLiteProfileStore.empty("fresh").themeManager();
		assertNull(imported.importTheme(export(UNSAVED_EDIT)[2]));
		assertEquals(expected.get(UNSAVED_EDIT), samples(imported.active()));
		assertEquals(expected.size(), checked + 1);
	}

	@Test
	public void disableEnableAndRestartKeepCustomThemesAndSelection() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main");
		ThemeManager manager = store.themeManager();
		List<Theme> original = manager.userThemes();
		String selected = manager.activeId();
		manager.setWorking(manager.effectiveTheme().withOpacity(13));

		// Plugin startUp() reloads the singleton; a client restart builds a new one.
		for (int cycle = 0; cycle < 3; cycle++)
		{
			manager.reload();
			assertEquals(original, manager.userThemes());
			assertEquals(selected, manager.activeId());
			assertFalse("unsaved edits are dropped, never saved", manager.isEdited());
		}
		ThemeManager restarted = store.themeManager();
		assertEquals(original, restarted.userThemes());
		assertEquals(selected, restarted.activeId());
		assertEquals(Collections.emptyList(), store.writes());

		String other = original.get(1).id;
		restarted.applyTheme(other);
		assertEquals(other, store.themeManager().activeId());
		restarted.applyTheme("midnight");
		assertEquals("midnight", store.themeManager().activeId());
	}

	// ---- Stage 3: import and export ----

	@Test
	public void releasedExportsImportWithEverySupportedSetting() throws IOException
	{
		Map<String, Theme> savedById = new LinkedHashMap<>();
		for (Theme theme : RuneLiteProfileStore.released("main").themeManager().userThemes())
		{
			savedById.put(theme.id, theme);
		}
		for (String[] export : releasedExports())
		{
			RuneLiteProfileStore target = RuneLiteProfileStore.released("alt");
			ThemeManager manager = target.themeManager();
			int before = manager.userThemes().size();
			assertNull(export[1], manager.importTheme(export[2]));
			Theme imported = manager.active();
			assertFalse(imported.builtIn);
			assertNotEquals("imports get a new identity", export[0], imported.id);
			assertEquals(export[1], imported.name);
			assertEquals(before + 1, manager.userThemes().size());
			assertMatchesReleased(decodeExport(export[2]), imported);
			if (savedById.containsKey(export[0]))
			{
				assertTrue(export[1], savedById.get(export[0]).sameStyle(imported));
			}
			assertEquals("the import is saved and selected", imported.id, target.themeManager().activeId());
		}
		Theme dark = BuiltInThemes.find("dark");
		ThemeManager manager = RuneLiteProfileStore.empty("fresh").themeManager();
		assertNull(manager.importTheme(export(UNSAVED_EDIT)[2]));
		Theme edited = manager.active();
		assertEquals(63, edited.opacity);
		assertEquals(63, edited.hoverOpacity);
		assertEquals(new Color(0x40E0D0), edited.accent);
		assertTrue(dark.withOpacity(63).withHoverOpacity(63).withAccent(new Color(0x40E0D0)).sameStyle(edited));
	}

	@Test
	public void releasedThemeReExportsAsTheReleasedFormatPlusHoverOpacity() throws IOException
	{
		ThemeManager manager = RuneLiteProfileStore.released("main").themeManager();
		for (String[] released : releasedExports())
		{
			if (released[0].equals(UNSAVED_EDIT))
			{
				continue;
			}
			manager.applyTheme(released[0]);
			String payload = manager.exportWorking();
			assertTrue(payload.startsWith("rightclickstyler:"));
			JsonObject current = decodeExport(payload);
			JsonObject original = decodeExport(released[2]);
			assertEquals(original.get("opacity"), current.get("hoverOpacity"));
			current.remove("hoverOpacity");
			assertEquals("every released property is exported unchanged", original, current);
		}
	}

	@Test
	public void newExportsKeepBothOpacitiesAcrossSaveRestartAndImport() throws IOException
	{
		int[][] pairs = {{0, 0}, {0, 100}, {100, 0}, {100, 100}, {1, 99}, {37, 81}, {40, 40}};
		for (int[] pair : pairs)
		{
			RuneLiteProfileStore store = RuneLiteProfileStore.released("main");
			ThemeManager manager = store.themeManager();
			manager.setWorking(manager.effectiveTheme().withOpacity(pair[0]).withHoverOpacity(pair[1]));
			manager.save();
			String payload = manager.exportWorking();
			Theme saved = manager.active();

			ThemeManager restarted = store.themeManager();
			assertEquals(pair[0], restarted.active().opacity);
			assertEquals(pair[1], restarted.active().hoverOpacity);
			assertTrue(saved.sameStyle(restarted.active()));
			assertEquals("Raid Night", restarted.active().name);

			RuneLiteProfileStore otherInstall = RuneLiteProfileStore.empty("fresh");
			ThemeManager importer = otherInstall.themeManager();
			assertNull(importer.importTheme(payload));
			ThemeManager importerRestarted = otherInstall.themeManager();
			Theme imported = importerRestarted.active();
			assertEquals(pair[0], imported.opacity);
			assertEquals(pair[1], imported.hoverOpacity);
			assertTrue(saved.sameStyle(imported));

			JsonObject reExport = decodeExport(importerRestarted.exportWorking());
			JsonObject first = decodeExport(payload);
			reExport.remove("id");
			first.remove("id");
			assertEquals("a new export round-trips completely", first, reExport);
		}
	}

	@Test
	public void importingSeveralExportsKeepsEachThemeAndDuplicatesStaySeparate() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.empty("fresh");
		ThemeManager manager = store.themeManager();
		List<String[]> exports = releasedExports();
		Set<String> ids = new HashSet<>();
		for (String[] export : exports)
		{
			assertNull(manager.importTheme(export[2]));
			assertTrue(ids.add(manager.activeId()));
			assertEquals("the most recent import is selected", export[1], manager.active().name);
		}
		// Documented: an import is always a separate theme with a new identity; names may repeat.
		assertNull(manager.importTheme(exports.get(0)[2]));
		assertTrue(ids.add(manager.activeId()));

		List<Theme> restored = store.themeManager().userThemes();
		assertEquals(exports.size() + 1, restored.size());
		for (int i = 0; i < exports.size(); i++)
		{
			assertEquals(exports.get(i)[1], restored.get(i).name);
		}
		assertEquals(restored.get(0).name, restored.get(exports.size()).name);
		assertTrue(restored.get(0).sameStyle(restored.get(exports.size())));
		assertNotEquals(restored.get(0).id, restored.get(exports.size()).id);
		assertEquals(manager.activeId(), store.themeManager().activeId());
	}

	@Test
	public void invalidImportsFailSafelyWithoutTouchingSavedData() throws IOException
	{
		String released = releasedExports().get(0)[2];
		List<String> invalid = new ArrayList<>(Arrays.asList(
			null, "", "   ", "rightclickstyler:", "rightclickstyler:!!!!", "not an export",
			released.substring(0, released.length() / 2),
			released.substring(0, released.length() - 7),
			"x".repeat(Limits.MAX_IMPORT_LENGTH + 1),
			encoded("[]"), encoded("null"), encoded("{}"), encoded("42"),
			encoded("{\"name\":\"No id\",\"opacity\":50}"),
			encoded("{\"id\":\"\",\"name\":\"Empty id\"}"),
			encoded("{\"id\":\"bad\",\"opacity\":\"high\"}"),
			encoded("{\"id\":\"bad\",\"hoverOpacity\":12.5}"),
			encoded("{\"id\":\"bad\",\"bg\":99999999999}"),
			encoded("{\"id\":\"bad\",\"showBorder\":{\"nested\":true}}"),
			encoded("{\"id\":\"bad\",\"name\":\"Unterminated\"")));

		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		ThemeManager manager = store.themeManager();
		List<Theme> themes = manager.userThemes();
		String selected = manager.activeId();
		Theme unsaved = manager.effectiveTheme().withOpacity(9);
		manager.setWorking(unsaved);
		Map<String, String> main = store.snapshot("main");
		Map<String, String> alt = store.snapshot("alt");

		for (String payload : invalid)
		{
			String label = payload == null ? "null" : payload.length() > 40 ? payload.substring(0, 40) : payload;
			assertNotNull(label, manager.importTheme(payload));
			assertEquals(label, themes, manager.userThemes());
			assertEquals(label, selected, manager.activeId());
			assertEquals("unsaved edits survive a failed import", unsaved, manager.effectiveTheme());
		}
		assertEquals(Collections.emptyList(), store.writes());
		assertEquals(main, store.snapshot("main"));
		assertEquals(alt, store.snapshot("alt"));
	}

	@Test
	public void importAtTheThemeLimitIsRejectedWithoutWriting() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main");
		ThemeManager manager = store.themeManager();
		String payload = releasedExports().get(0)[2];
		while (manager.userThemes().size() < Limits.MAX_USER_THEMES)
		{
			assertNull(manager.importTheme(payload));
		}
		manager.applyTheme(manager.userThemes().get(1).id);
		Map<String, String> full = store.snapshot("main");
		store.clearWrites();
		assertEquals(ThemeManager.ERROR_FULL, manager.importTheme(payload));
		assertEquals(Collections.emptyList(), store.writes());
		assertEquals(full, store.snapshot("main"));
	}

	@Test
	public void missingAndUnknownPropertiesUseBackwardCompatibleDefaults()
	{
		Theme d = BuiltInThemes.find(BuiltInThemes.DEFAULT_ID);

		ThemeManager minimal = RuneLiteProfileStore.empty("fresh").themeManager();
		assertNull(minimal.importTheme(encoded("{\"id\":\"only-id\"}")));
		assertTrue("missing properties come from the default theme", d.sameStyle(minimal.active()));
		assertEquals(d.opacity, minimal.active().hoverOpacity);
		assertEquals("Theme", minimal.active().name);

		ThemeManager partial = RuneLiteProfileStore.empty("fresh").themeManager();
		assertNull(partial.importTheme(encoded("{\"id\":\"p\",\"name\":\"Partial\",\"opacity\":65,\"radius\":3}")));
		assertEquals(65, partial.active().opacity);
		assertEquals("legacy themes inherit their background opacity", 65, partial.active().hoverOpacity);
		assertEquals(3, partial.active().radius);
		assertEquals(d.bg, partial.active().bg);

		ThemeManager future = RuneLiteProfileStore.empty("fresh").themeManager();
		assertNull(future.importTheme(encoded("{\"id\":\"f\",\"name\":\"Future\",\"opacity\":70,"
			+ "\"hoverOpacity\":20,\"schemaVersion\":9,\"shape\":\"pill\",\"gradient\":{\"from\":1,\"to\":2},"
			+ "\"tags\":[\"a\",\"b\"],\"font\":\"COMIC\",\"hoverStyle\":\"SPARKLE\"}")));
		Theme t = future.active();
		assertEquals(70, t.opacity);
		assertEquals(20, t.hoverOpacity);
		assertEquals("unknown enum values fall back", d.font, t.font);
		assertEquals(d.hoverStyle, t.hoverStyle);
		JsonObject reExport = decodeExport(future.exportWorking());
		assertFalse("unknown properties are not carried forward", reExport.has("shape"));
		assertEquals(new HashSet<>(fieldsWithHoverOpacity()), reExport.keySet());
	}

	// ---- Stage 4: RuneLite profiles ----

	@Test
	public void profileSwitchesReloadTheRightProfileAndNeverWriteAcrossProfiles() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		Map<String, String> mainBefore = store.snapshot("main");
		Map<String, String> altBefore = store.snapshot("alt");
		ThemeManager manager = store.themeManager();
		RightClickStylerConfig config = store.config();
		List<Theme> mainThemes = manager.userThemes();
		String mainSelected = manager.activeId();
		manager.setWorking(manager.effectiveTheme().withOpacity(3).withHoverOpacity(97));

		store.switchTo("alt");
		manager.reload();
		assertEquals(1, manager.userThemes().size());
		assertEquals("Profile Two Theme", manager.userThemes().get(0).name);
		assertEquals("glass", manager.activeId());
		assertFalse("unsaved edits from the previous profile are not carried over", manager.isEdited());
		assertEquals(TargetMode.GAME, config.targetMode());
		assertEquals(new Color(0x00FFFF), config.itemColour());
		assertEquals(Collections.emptyList(), store.writes());

		// Changes made in the alt profile stay there.
		assertTrue(manager.saveAsNew("Alt only"));
		store.set(RightClickStylerConfig.KEY_NPC_COLOUR, new Color(0x123456));
		assertEquals(mainBefore, store.snapshot("main"));

		store.switchTo("main");
		manager.reload();
		assertEquals(mainThemes, manager.userThemes());
		assertEquals(mainSelected, manager.activeId());
		assertFalse(manager.isEdited());
		assertEquals(new Color(0x00FF80), config.npcColour());
		assertEquals(TargetMode.CUSTOM, config.targetMode());

		store.switchTo("alt");
		ThemeManager altRestart = store.themeManager();
		assertEquals(2, altRestart.userThemes().size());
		assertEquals("Alt only", altRestart.active().name);
		assertEquals(new Color(0x123456), config.npcColour());
		assertEquals(altBefore.get(PREFIX + RightClickStylerConfig.KEY_ITEM_COLOUR),
			store.get(RightClickStylerConfig.KEY_ITEM_COLOUR));

		store.switchTo("new profile");
		manager.reload();
		assertTrue(manager.userThemes().isEmpty());
		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		assertEquals(new Color(0xFF9040), config.itemColour());
		assertEquals(mainBefore, store.snapshot("main"));
	}

	@Test
	public void persistedSettingChangesInvalidateTheSubmenuImage() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		ThemeManager manager = store.themeManager();
		SubmenuImageCache.Key base = cacheKey(manager, store);
		assertEquals(SubmenuImageCache.Miss.HIT, cacheKey(manager, store).difference(base));

		store.switchTo("alt");
		manager.reload();
		assertNotEquals(SubmenuImageCache.Miss.HIT, cacheKey(manager, store).difference(base));
		store.switchTo("main");
		manager.reload();
		assertEquals("a reload rebuilds the theme, so the image is redrawn",
			SubmenuImageCache.Miss.THEME, cacheKey(manager, store).difference(base));

		SubmenuImageCache.Key reloaded = cacheKey(manager, store);
		RightClickStylerPanel.resetTargetColourSettings(store::unset);
		assertEquals(SubmenuImageCache.Miss.CONFIG, cacheKey(manager, store).difference(reloaded));

		SubmenuImageCache.Key reset = cacheKey(manager, store);
		store.set(RightClickStylerConfig.KEY_ITEM_COLOUR, new Color(0x010203));
		assertEquals(SubmenuImageCache.Miss.CONFIG, cacheKey(manager, store).difference(reset));

		SubmenuImageCache.Key coloured = cacheKey(manager, store);
		manager.setWorking(manager.effectiveTheme().withHoverOpacity(manager.effectiveTheme().hoverOpacity - 1));
		assertEquals(SubmenuImageCache.Miss.THEME, cacheKey(manager, store).difference(coloured));
	}

	// ---- Stage 5: reset actions ----

	@Test
	public void resetTargetColoursOnlyClearsTheFiveTargetColours() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		ThemeManager manager = store.themeManager();
		List<Theme> themes = manager.userThemes();
		Map<String, String> before = store.snapshot("main");
		Map<String, String> alt = store.snapshot("alt");

		RightClickStylerPanel.resetTargetColourSettings(store::unset);

		Map<String, String> expected = new TreeMap<>(before);
		for (String key : new String[]{"npcColour", "playerColour", "itemColour", "objectColour", "otherColour"})
		{
			assertNotNull("fixture had a custom " + key, expected.remove(PREFIX + key));
		}
		assertEquals(expected, store.snapshot("main"));
		assertEquals(alt, store.snapshot("alt"));
		RightClickStylerConfig config = store.config();
		assertEquals(TargetMode.CUSTOM, config.targetMode());
		assertEquals(new Color(0xFF9040), config.itemColour());
		assertEquals(new Color(0xFFFF00), config.npcColour());
		assertEquals(new Color(0xDD0000), config.higherLevelColour());
		assertEquals(5, config.textOffsetX());
		manager.reload();
		assertEquals("themes, opacity and formatting are untouched", themes, manager.userThemes());
	}

	@Test
	public void resetAllKeepsSavedThemesAndRestoresDefaultsInThisProfileOnly() throws IOException
	{
		RuneLiteProfileStore store = RuneLiteProfileStore.released("main", "alt");
		ThemeManager manager = store.themeManager();
		List<Theme> themes = manager.userThemes();
		String savedThemes = store.get(RightClickStylerConfig.KEY_USER_THEMES);
		Map<String, String> alt = store.snapshot("alt");
		manager.setWorking(manager.effectiveTheme().withRadius(1));

		RightClickStylerPanel.resetAllSettings(store::unset, manager);

		assertEquals("saved themes are kept byte for byte", savedThemes,
			store.get(RightClickStylerConfig.KEY_USER_THEMES));
		Map<String, String> after = store.snapshot("main");
		assertEquals(new HashSet<>(Arrays.asList(PREFIX + RightClickStylerConfig.KEY_USER_THEMES,
			PREFIX + RightClickStylerConfig.KEY_ACTIVE_THEME)), after.keySet());
		assertEquals(BuiltInThemes.DEFAULT_ID, after.get(PREFIX + RightClickStylerConfig.KEY_ACTIVE_THEME));
		assertEquals(alt, store.snapshot("alt"));
		RightClickStylerConfig config = store.config();
		RightClickStylerConfig defaults = RuneLiteProfileStore.empty("defaults").config();
		for (Method method : RightClickStylerConfig.class.getDeclaredMethods())
		{
			if (method.getAnnotation(ConfigItem.class) != null)
			{
				try
				{
					assertEquals(method.getName(), method.invoke(defaults), method.invoke(config));
				}
				catch (ReflectiveOperationException e)
				{
					throw new AssertionError(e);
				}
			}
		}
		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		assertFalse(manager.isEdited());
		assertEquals(themes, manager.userThemes());

		ThemeManager restarted = store.themeManager();
		assertEquals(themes, restarted.userThemes());
		assertEquals(BuiltInThemes.DEFAULT_ID, restarted.activeId());
		store.switchTo("alt");
		assertEquals(new Color(0x00FFFF), config.itemColour());
		assertEquals("glass", store.themeManager().activeId());
	}

	// ---- helpers ----

	private static SubmenuImageCache.Key cacheKey(ThemeManager manager, RuneLiteProfileStore store)
	{
		List<MenuRenderer.Row> rows = Collections.singletonList(
			new MenuRenderer.Row("Teleport", "<col=ff9040>Drakan's medallion", MenuRenderer.TargetKind.ITEM));
		SubmenuImageCache.Menu menu = SubmenuImageCache.menu(new Rectangle(10, 10, 150, 34),
			"Choose Option", 0, -1, rows);
		return SubmenuImageCache.capture(manager.effectiveTheme(), store.config(), null, 90,
			new Rectangle(0, 0, 300, 200), new SubmenuImageCache.Menu[]{menu});
	}

	private static List<String> fieldsWithHoverOpacity()
	{
		List<String> fields = new ArrayList<>(RELEASED_THEME_FIELDS);
		fields.add("hoverOpacity");
		return fields;
	}

	private static void assertMatchesReleased(JsonObject json, Theme t)
	{
		String n = json.get("name").getAsString();
		assertEquals(n, json.get("bg").getAsInt(), t.bg.getRGB());
		assertEquals(n, json.get("border").getAsInt(), t.border.getRGB());
		assertEquals(n, json.get("header").getAsInt(), t.header.getRGB());
		assertEquals(n, json.get("headerText").getAsInt(), t.headerText.getRGB());
		assertEquals(n, json.get("text").getAsInt(), t.text.getRGB());
		assertEquals(n, json.get("hover").getAsInt(), t.hover.getRGB());
		assertEquals(n, json.get("accent").getAsInt(), t.accent.getRGB());
		assertEquals(n, json.get("radius").getAsInt(), t.radius);
		assertEquals(n, json.get("opacity").getAsInt(), t.opacity);
		assertEquals(n + ": legacy hover opacity inherits opacity", json.get("opacity").getAsInt(), t.hoverOpacity);
		assertEquals(n, json.get("hoverStyle").getAsString(), t.hoverStyle.name());
		assertEquals(n, json.get("showBorder").getAsBoolean(), t.showBorder);
		assertEquals(n, json.get("showShadow").getAsBoolean(), t.showShadow);
		assertEquals(n, json.get("showHeader").getAsBoolean(), t.showHeader);
		assertEquals(n, json.get("font").getAsString(), t.font.name());
		assertEquals(n, json.get("fontSize").getAsInt(), t.fontSize);
		assertEquals(n, json.get("textShadow").getAsBoolean(), t.textShadow);
		assertEquals(n, t.name);
		assertFalse(t.builtIn);
	}

	private static JsonArray releasedThemes(RuneLiteProfileStore store)
	{
		return GSON.fromJson(store.get(RightClickStylerConfig.KEY_USER_THEMES), JsonArray.class);
	}

	private static JsonObject decodeExport(String payload)
	{
		String body = payload.substring("rightclickstyler:".length());
		return GSON.fromJson(new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8), JsonObject.class);
	}

	private static String encoded(String json)
	{
		return "rightclickstyler:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static String[] export(String id) throws IOException
	{
		for (String[] export : releasedExports())
		{
			if (export[0].equals(id))
			{
				return export;
			}
		}
		throw new AssertionError("no released export " + id);
	}

	private static List<String[]> releasedExports() throws IOException
	{
		List<String[]> exports = new ArrayList<>();
		for (String line : lines("released/released-exports.txt"))
		{
			exports.add(line.split("\t", 3));
		}
		assertEquals(5, exports.size());
		return exports;
	}

	private static Map<String, String> releasedPixels() throws IOException
	{
		Map<String, String> pixels = new LinkedHashMap<>();
		for (String line : lines("released/released-pixels.txt"))
		{
			String[] parts = line.split("\t", 2);
			pixels.put(parts[0], parts[1]);
		}
		return pixels;
	}

	private static List<String> lines(String resource) throws IOException
	{
		List<String> lines = new ArrayList<>();
		try (InputStream in = ReleasedUpgradeCompatibilityTest.class.getResourceAsStream(resource))
		{
			assertNotNull(resource, in);
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			for (String line; (line = reader.readLine()) != null; )
			{
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					lines.add(line);
				}
			}
		}
		return lines;
	}

	private static Class<?> unboxed(Class<?> type)
	{
		if (type == Boolean.class)
		{
			return boolean.class;
		}
		if (type == Integer.class)
		{
			return int.class;
		}
		if (Color.class.isAssignableFrom(type))
		{
			return Color.class;
		}
		return type;
	}

	/** Same geometry and sample points as the released generator. */
	private static String samples(Theme theme)
	{
		BufferedImage image = new BufferedImage(240, 90, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setColor(new Color(0x404040));
			g.fillRect(0, 0, 240, 90);
			List<MenuRenderer.Row> rows = Arrays.asList(
				new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER),
				new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER),
				new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER));
			new MenuRenderer().render(g, null, theme, new RightClickStylerConfig() { }, rows,
				new Rectangle(10, 10, 200, 19 + 45), 1, 0);
		}
		finally
		{
			g.dispose();
		}
		int[][] points = {{195, 14}, {195, 36}, {11, 51}, {195, 51}, {195, 66}};
		StringBuilder sb = new StringBuilder();
		for (int[] pt : points)
		{
			if (sb.length() > 0)
			{
				sb.append(',');
			}
			sb.append(pt[0]).append(':').append(pt[1]).append('=')
				.append(Integer.toHexString(image.getRGB(pt[0], pt[1])));
		}
		return sb.toString();
	}
}
