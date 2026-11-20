package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.Color;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ThemeManagerTest
{
	@Test
	public void rejectsMalformedAndOversizedImports()
	{
		TestConfig config = new TestConfig();
		ThemeManager manager = manager(config);

		assertNotNull(manager.importTheme("not base64"));
		assertNotNull(manager.importTheme("x".repeat(Limits.MAX_IMPORT_LENGTH + 1)));
		assertTrue(manager.userThemes().isEmpty());
		assertEquals(0, config.writes);
	}

	@Test
	public void exportsUnsavedWorkingEditsWithoutOverwritingBuiltIns()
	{
		TestConfig sourceConfig = new TestConfig();
		ThemeManager source = manager(sourceConfig);
		Color editedBackground = new Color(0x123456);
		source.setWorking(source.effectiveTheme().withBg(editedBackground));

		String payload = source.exportWorking();

		assertEquals(0, sourceConfig.writes);
		TestConfig targetConfig = new TestConfig();
		ThemeManager target = manager(targetConfig);
		assertNull(target.importTheme(payload));
		assertNotEquals(BuiltInThemes.DEFAULT_ID, target.activeId());
		assertFalse(target.active().builtIn);
		assertEquals(editedBackground, target.active().bg);
		assertTrue(BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).builtIn);
		assertNotEquals(editedBackground, BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).bg);
	}

	@Test
	public void opacityRoundTripsThroughPersistenceAndClipboard()
	{
		for (int opacity : new int[]{0, 1, 10, 40, 100})
		{
			TestConfig config = new TestConfig();
			ThemeManager source = manager(config);
			source.setWorking(source.effectiveTheme().withOpacity(opacity));
			assertTrue(source.saveAsNew("Opacity " + opacity));
			assertEquals(opacity, manager(config).active().opacity);

			ThemeManager target = manager(new TestConfig());
			assertNull(target.importTheme(source.exportWorking()));
			assertEquals(opacity, target.active().opacity);
		}
	}

	@Test
	public void importedOpacityClampsToFullRangeAndMissingValueKeepsDefault()
	{
		for (int[] caseValue : new int[][]{{-1, 0}, {0, 0}, {100, 100}, {101, 100}})
		{
			ThemeManager target = manager(new TestConfig());
			String json = "{\"id\":\"imported\",\"name\":\"Imported\",\"opacity\":"
				+ caseValue[0] + "}";
			assertNull(target.importTheme(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
			assertEquals(caseValue[1], target.active().opacity);
		}
		ThemeManager target = manager(new TestConfig());
		String json = "{\"id\":\"imported\",\"name\":\"Imported\"}";
		assertNull(target.importTheme(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
		assertEquals(BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).opacity, target.active().opacity);
	}

	@Test
	public void legacyThemesInheritHoverOpacityFromBackgroundOpacity()
	{
		for (int opacity : new int[]{40, 75, 100})
		{
			String json = "{\"id\":\"legacy\",\"name\":\"Legacy\",\"opacity\":" + opacity + "}";
			ThemeManager imported = manager(new TestConfig());
			assertNull(imported.importTheme(encoded(json)));
			assertEquals(opacity, imported.active().opacity);
			assertEquals(opacity, imported.active().hoverOpacity);

			TestConfig saved = new TestConfig();
			saved.current().values.put(RightClickStylerConfig.KEY_USER_THEMES, "[" + json + "]");
			saved.current().values.put(RightClickStylerConfig.KEY_ACTIVE_THEME, "legacy");
			Theme loaded = manager(saved).active();
			assertEquals(opacity, loaded.opacity);
			assertEquals(opacity, loaded.hoverOpacity);
		}
	}

	@Test
	public void independentOpacitiesRoundTripAndSurviveThemeActions()
	{
		TestConfig config = new TestConfig();
		ThemeManager manager = manager(config);
		manager.applyTheme("glass");
		assertEquals(40, manager.active().hoverOpacity);
		assertTrue(manager.saveAsNew("Glass copy"));
		String glassCopyId = manager.activeId();
		assertEquals(40, manager.active().opacity);
		assertEquals(40, manager.active().hoverOpacity);

		manager.setWorking(manager.effectiveTheme().withOpacity(0).withHoverOpacity(80));
		assertTrue(manager.isEdited());
		manager.save();
		assertFalse(manager.isEdited());
		assertEquals(0, manager.active().opacity);
		assertEquals(80, manager.active().hoverOpacity);
		Theme restored = manager(config).active();
		assertEquals(0, restored.opacity);
		assertEquals(80, restored.hoverOpacity);

		ThemeManager imported = manager(new TestConfig());
		assertNull(imported.importTheme(manager.exportWorking()));
		assertEquals(0, imported.active().opacity);
		assertEquals(80, imported.active().hoverOpacity);
		assertTrue(imported.exportWorking().startsWith("rightclickstyler:"));

		manager.setWorking(manager.effectiveTheme().withOpacity(30));
		assertEquals(80, manager.effectiveTheme().hoverOpacity);
		manager.revert();
		assertEquals(0, manager.effectiveTheme().opacity);
		assertEquals(80, manager.effectiveTheme().hoverOpacity);
		manager.applyTheme(BuiltInThemes.DEFAULT_ID);
		assertEquals(100, manager.effectiveTheme().hoverOpacity);
		manager.applyTheme(glassCopyId);
		assertEquals(0, manager.effectiveTheme().opacity);
		assertEquals(80, manager.effectiveTheme().hoverOpacity);
		config.resetAnnotatedConfiguration();
		manager.reload();
		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		manager.applyTheme(glassCopyId);
		assertEquals(0, manager.effectiveTheme().opacity);
		assertEquals(80, manager.effectiveTheme().hoverOpacity);
	}

	@Test
	public void hoverOpacityIsIndependentInCopiesEqualityAndBuiltIns()
	{
		for (Theme builtIn : BuiltInThemes.ALL)
		{
			assertEquals(builtIn.name, builtIn.opacity, builtIn.hoverOpacity);
		}
		Theme original = BuiltInThemes.find("glass");
		Theme changedHover = original.withHoverOpacity(75);
		assertEquals(40, changedHover.opacity);
		assertEquals(75, changedHover.hoverOpacity);
		assertEquals(75, changedHover.withOpacity(0).hoverOpacity);
		assertFalse(original.sameStyle(changedHover));
		assertNotEquals(original, changedHover);
		assertEquals(75, changedHover.withBg(Color.BLUE).hoverOpacity);
	}

	@Test
	public void importedHoverOpacityClampsAtBothLimits()
	{
		for (int[] pair : new int[][]{{-1, 0}, {0, 0}, {100, 100}, {101, 100}})
		{
			ThemeManager manager = manager(new TestConfig());
			String json = "{\"id\":\"hover\",\"opacity\":30,\"hoverOpacity\":" + pair[0] + "}";
			assertNull(manager.importTheme(encoded(json)));
			assertEquals(30, manager.active().opacity);
			assertEquals(pair[1], manager.active().hoverOpacity);
		}
	}

	@Test
	public void reloadReplacesThemeStateWithoutWritingStaleEdits()
	{
		TestConfig config = new TestConfig();
		ThemeManager manager = manager(config);
		assertTrue(manager.saveAsNew("Profile one"));
		manager.setWorking(manager.effectiveTheme().withOpacity(51));
		int writesBeforeReload = config.writes;

		config.current().values.put(RightClickStylerConfig.KEY_USER_THEMES, "[]");
		config.current().values.put(RightClickStylerConfig.KEY_ACTIVE_THEME, BuiltInThemes.DEFAULT_ID);
		manager.reload();

		assertEquals(writesBeforeReload, config.writes);
		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		assertTrue(manager.active().builtIn);
		assertFalse(manager.isEdited());
		assertTrue(manager.userThemes().isEmpty());
	}

	@Test
	public void persistedThemesUseOriginalKeyAndSurviveConfigurationReset()
	{
		TestConfig config = new TestConfig();
		config.current().showSidebarButton = false;
		config.current().highlightsEnabled = false;
		config.current().targetMode = TargetMode.CUSTOM;
		ThemeManager manager = manager(config);
		assertTrue(manager.saveAsNew("Saved theme"));
		String savedId = manager.activeId();

		assertTrue(config.current().values.containsKey(RightClickStylerConfig.KEY_USER_THEMES));
		config.resetAnnotatedConfiguration();
		manager.reload();

		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		assertTrue(manager.active().builtIn);
		assertEquals(1, manager.userThemes().size());
		assertEquals(savedId, manager.userThemes().get(0).id);
		assertTrue(config.showSidebarButton());
		assertTrue(config.highlightsEnabled());
		assertEquals(TargetMode.GAME, config.targetMode());
	}

	@Test
	public void userThemesAreNotAnAnnotatedResetItem()
	{
		for (Method method : RightClickStylerConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				assertNotEquals(RightClickStylerConfig.KEY_USER_THEMES, item.keyName());
			}
		}
	}

	@Test
	public void reloadKeepsProfilesIsolated()
	{
		TestConfig config = new TestConfig();
		ThemeManager manager = manager(config);
		assertTrue(manager.saveAsNew("Profile one"));
		String profileOneId = manager.activeId();

		config.switchProfile("two");
		manager.reload();
		assertTrue(manager.userThemes().isEmpty());
		assertEquals(BuiltInThemes.DEFAULT_ID, manager.activeId());
		assertTrue(manager.saveAsNew("Profile two"));
		String profileTwoId = manager.activeId();

		config.switchProfile("one");
		manager.reload();
		assertEquals(profileOneId, manager.activeId());
		assertEquals(profileOneId, manager.userThemes().get(0).id);
		assertNotEquals(profileTwoId, manager.userThemes().get(0).id);
		assertFalse(manager.isEdited());
	}

	private static ThemeManager manager(TestConfig config)
	{
		return new ThemeManager(config::readThemes, config::write, config, new Gson());
	}

	private static String encoded(String json)
	{
		return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}

	private static final class TestConfig implements RightClickStylerConfig
	{
		private final Map<String, ProfileState> profiles = new HashMap<>();
		private String profile = "one";
		private int writes;

		private TestConfig()
		{
			profiles.put(profile, new ProfileState());
		}

		@Override
		public String activeTheme()
		{
			return current().values.getOrDefault(RightClickStylerConfig.KEY_ACTIVE_THEME,
				BuiltInThemes.DEFAULT_ID);
		}

		@Override
		public boolean showSidebarButton()
		{
			return current().showSidebarButton;
		}

		@Override
		public boolean highlightsEnabled()
		{
			return current().highlightsEnabled;
		}

		@Override
		public TargetMode targetMode()
		{
			return current().targetMode;
		}

		private String readThemes()
		{
			return current().values.getOrDefault(RightClickStylerConfig.KEY_USER_THEMES, "[]");
		}

		private void write(String key, String value)
		{
			writes++;
			current().values.put(key, value);
		}

		private void resetAnnotatedConfiguration()
		{
			current().values.remove(RightClickStylerConfig.KEY_ACTIVE_THEME);
			current().showSidebarButton = true;
			current().highlightsEnabled = true;
			current().targetMode = TargetMode.GAME;
		}

		private void switchProfile(String profile)
		{
			this.profile = profile;
			profiles.computeIfAbsent(profile, key -> new ProfileState());
		}

		private ProfileState current()
		{
			return profiles.get(profile);
		}
	}

	private static final class ProfileState
	{
		private final Map<String, String> values = new HashMap<>();
		private boolean showSidebarButton = true;
		private boolean highlightsEnabled = true;
		private TargetMode targetMode = TargetMode.GAME;
	}
}
