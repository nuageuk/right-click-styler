package com.rightclickstyler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TargetColourTest
{
	private static final Color NATIVE_ITEM = new Color(0xFF9040);
	private static final Color NATIVE_NPC = Color.YELLOW;
	private static final Color CUSTOM_ITEM = new Color(0xE700FF);
	private static final Color CUSTOM_NPC = new Color(0x00C0FF);
	private static final Color CUSTOM_OTHER = new Color(0x808080);
	private static final Color LOWER_LEVEL = new Color(0x00FF80);
	private static final int LOCAL_LEVEL = 50;

	@Test
	public void customItemColourReplacesSingleNativeInventoryTag()
	{
		assertRuns(custom(), "<col=ff9040>Drakan's medallion", MenuRenderer.TargetKind.ITEM,
			"Drakan's medallion", CUSTOM_ITEM);
	}

	@Test
	public void customItemColourReplacesLootFiltersDuplicateNativeTag()
	{
		assertRuns(custom(), "<col=ff9040><col=ff9040>Drakan's medallion</col>", MenuRenderer.TargetKind.ITEM,
			"Drakan's medallion", CUSTOM_ITEM);
		assertRuns(custom(), "<col=ff9040><col=ff9040>Coins (1.2K)</col>", MenuRenderer.TargetKind.ITEM,
			"Coins (1.2K)", CUSTOM_ITEM);
	}

	@Test
	public void explicitThirdPartyColoursArePreserved()
	{
		// Loot Filters rule colour written after the native tag
		assertRuns(custom(), "<col=ff9040><col=00ff00>Rune scimitar</col>", MenuRenderer.TargetKind.ITEM,
			"Rune scimitar", Color.GREEN);
		// Ground Items highlight, which replaces the native tag
		assertRuns(custom(), "<col=ffff00>Coins", MenuRenderer.TargetKind.ITEM,
			"Coins", Color.YELLOW);
		// The item orange is not a native colour for other kinds
		assertRuns(custom(), "<col=ff9040>Lobster", MenuRenderer.TargetKind.OTHER,
			"Lobster", NATIVE_ITEM);
	}

	@Test
	public void laterTextSpansKeepTheirOwnColour()
	{
		assertRuns(custom(), "<col=ff9040>Coins</col><col=ffffff> (100)", MenuRenderer.TargetKind.ITEM,
			"Coins", CUSTOM_ITEM, " (100)", Color.WHITE);
		assertRuns(custom(), "<col=ff9040><col=ff9040>Coins</col><col=ff9040> (100)",
			MenuRenderer.TargetKind.ITEM,
			"Coins", CUSTOM_ITEM, " (100)", NATIVE_ITEM);
	}

	@Test
	public void uncolouredPrefixMakesTheFirstTagALaterSpan()
	{
		assertRuns(custom(), "Noted <col=ff9040>Bones", MenuRenderer.TargetKind.ITEM,
			"Noted ", CUSTOM_ITEM, "Bones", NATIVE_ITEM);
	}

	@Test
	public void iconWithoutTextDoesNotStartANewSpan()
	{
		assertRuns(custom(), "<col=ff9040><img=3><col=ff9040>Coins", MenuRenderer.TargetKind.ITEM,
			"Coins", CUSTOM_ITEM);
		assertRuns(custom(), "<col=ff9040>Coins<img=3><col=ff9040> (100)", MenuRenderer.TargetKind.ITEM,
			"Coins", CUSTOM_ITEM, " (100)", NATIVE_ITEM);
	}

	@Test
	public void duplicateNativeNpcTagTakesCustomColourAndKeepsCustomLevelColour()
	{
		assertRuns(custom(), "<col=ffff00><col=ffff00>Guard</col><col=ff00>  (level-21)",
			MenuRenderer.TargetKind.NPC,
			"Guard", CUSTOM_NPC, "(level-21)", LOWER_LEVEL);
	}

	@Test
	public void gameModeKeepsEveryTagColour()
	{
		RightClickStylerConfig game = config(TargetMode.GAME, CombatLevelMode.GAME);
		assertRuns(game, "<col=ff9040>Drakan's medallion", MenuRenderer.TargetKind.ITEM,
			"Drakan's medallion", NATIVE_ITEM);
		assertRuns(game, "<col=ff9040><col=ff9040>Drakan's medallion</col>", MenuRenderer.TargetKind.ITEM,
			"Drakan's medallion", NATIVE_ITEM);
		assertRuns(game, "<col=ff9040><col=00ff00>Rune scimitar</col>", MenuRenderer.TargetKind.ITEM,
			"Rune scimitar", Color.GREEN);
		assertRuns(game, "<col=ffff00><col=ffff00>Guard</col><col=ff00>  (level-21)",
			MenuRenderer.TargetKind.NPC,
			"Guard", NATIVE_NPC, "(level-21)", Color.GREEN);
	}

	private static RightClickStylerConfig custom()
	{
		return config(TargetMode.CUSTOM, CombatLevelMode.CUSTOM);
	}

	private static RightClickStylerConfig config(TargetMode targetMode, CombatLevelMode levelMode)
	{
		RightClickStylerConfig config = mock(RightClickStylerConfig.class);
		when(config.targetMode()).thenReturn(targetMode);
		when(config.combatLevelMode()).thenReturn(levelMode);
		when(config.itemColour()).thenReturn(CUSTOM_ITEM);
		when(config.npcColour()).thenReturn(CUSTOM_NPC);
		when(config.playerColour()).thenReturn(Color.PINK);
		when(config.objectColour()).thenReturn(Color.ORANGE);
		when(config.otherColour()).thenReturn(CUSTOM_OTHER);
		when(config.lowerLevelColour()).thenReturn(LOWER_LEVEL);
		when(config.sameLevelColour()).thenReturn(Color.LIGHT_GRAY);
		when(config.higherLevelColour()).thenReturn(Color.RED);
		return config;
	}

	/** Expected alternates each visible run's text with its colour, in drawing order. */
	private static void assertRuns(RightClickStylerConfig config, String target, MenuRenderer.TargetKind kind,
		Object... expected)
	{
		List<Map.Entry<String, Color>> runs = new MenuRenderer().targetRunColours(config, target, kind, LOCAL_LEVEL);
		List<Object> actual = new ArrayList<>();
		for (Map.Entry<String, Color> run : runs)
		{
			actual.add(run.getKey());
			actual.add(run.getValue());
		}
		assertEquals(target, Arrays.asList(expected), actual);
	}
}
