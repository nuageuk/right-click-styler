package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RightClickStylerPanelTest
{
	private static final RightClickStylerConfig DEFAULTS = new RightClickStylerConfig() {};
	private static final RightClickStylerConfig CUSTOMISED = new RightClickStylerConfig()
	{
		@Override
		public Color itemColour()
		{
			return Color.MAGENTA;
		}

		@Override
		public Color higherLevelColour()
		{
			return Color.MAGENTA;
		}
	};

	@Test
	public void combatColourResetUnsetsOnlyTheLevelColours()
	{
		List<String> unset = new ArrayList<>();
		RightClickStylerPanel.resetCombatColourSettings(unset::add);
		assertEquals(Arrays.asList(RightClickStylerConfig.KEY_LOWER_LEVEL_COLOUR,
			RightClickStylerConfig.KEY_SAME_LEVEL_COLOUR, RightClickStylerConfig.KEY_HIGHER_LEVEL_COLOUR), unset);
		assertFalse(unset.contains(RightClickStylerConfig.KEY_COMBAT_LEVEL_MODE));
	}

	@Test
	public void targetColourResetKeepsTheTargetMode()
	{
		List<String> unset = new ArrayList<>();
		RightClickStylerPanel.resetTargetColourSettings(unset::add);
		assertEquals(5, unset.size());
		assertFalse(unset.contains(RightClickStylerConfig.KEY_TARGET_MODE));
	}

	@Test
	public void cancelledColourResetChangesNothing()
	{
		int[] asked = {0};
		boolean reset = RightClickStylerPanel.resetColoursIfConfirmed(
			RightClickStylerPanel.combatColours(CUSTOMISED), RightClickStylerPanel.combatColours(DEFAULTS),
			() ->
			{
				asked[0]++;
				return false;
			}, () -> fail("reset after Cancel"));
		assertFalse(reset);
		assertEquals(1, asked[0]);
	}

	@Test
	public void confirmedColourResetRunsTheReset()
	{
		int[] resets = {0};
		assertTrue(RightClickStylerPanel.resetColoursIfConfirmed(
			RightClickStylerPanel.targetColours(CUSTOMISED), RightClickStylerPanel.targetColours(DEFAULTS),
			() -> true, () -> resets[0]++));
		assertEquals(1, resets[0]);
	}

	@Test
	public void colourResetAtDefaultsDoesNotAsk()
	{
		BooleanSupplier neverAsk = () ->
		{
			throw new AssertionError("asked to confirm with nothing to reset");
		};
		assertFalse(RightClickStylerPanel.resetColoursIfConfirmed(RightClickStylerPanel.targetColours(DEFAULTS),
			RightClickStylerPanel.targetColours(DEFAULTS), neverAsk, () -> fail("reset at defaults")));
		assertFalse(RightClickStylerPanel.resetColoursIfConfirmed(RightClickStylerPanel.combatColours(DEFAULTS),
			RightClickStylerPanel.combatColours(DEFAULTS), neverAsk, () -> fail("reset at defaults")));
	}

	@Test
	public void cancelledRevertKeepsTheUnsavedEdits()
	{
		ThemeManager themes = themeManager();
		Theme edited = themes.effectiveTheme().withText(Color.MAGENTA);
		themes.setWorking(edited);

		assertFalse(RightClickStylerPanel.revertEdits(themes, () -> false));
		assertSame(edited, themes.effectiveTheme());
		assertTrue(themes.isEdited());
	}

	@Test
	public void confirmedRevertRestoresTheSavedTheme()
	{
		ThemeManager themes = themeManager();
		themes.setWorking(themes.effectiveTheme().withText(Color.MAGENTA));

		assertTrue(RightClickStylerPanel.revertEdits(themes, () -> true));
		assertFalse(themes.isEdited());
		assertTrue(themes.effectiveTheme().sameStyle(themes.active()));
	}

	@Test
	public void revertWithoutUnsavedEditsDoesNotAsk()
	{
		ThemeManager themes = themeManager();
		assertTrue(RightClickStylerPanel.revertEdits(themes, () ->
		{
			throw new AssertionError("asked to confirm with nothing to discard");
		}));
		assertFalse(themes.isEdited());
	}

	private static ThemeManager themeManager()
	{
		return new ThemeManager(() -> null, (key, value) ->
		{
		}, mock(RightClickStylerConfig.class), new Gson());
	}
}
