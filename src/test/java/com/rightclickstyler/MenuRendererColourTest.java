package com.rightclickstyler;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuRendererColourTest
{
	private static final Color CUSTOM = new Color(0x3300FF);
	private static final Color LOOT_FILTER = new Color(0x39C949);
	private static final Color ID_SUFFIX = new Color(0xDFAB23);
	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig()
	{
		@Override
		public TargetMode targetMode()
		{
			return TargetMode.CUSTOM;
		}

		@Override
		public Color npcColour()
		{
			return CUSTOM;
		}

		@Override
		public Color playerColour()
		{
			return CUSTOM;
		}

		@Override
		public Color itemColour()
		{
			return CUSTOM;
		}

		@Override
		public Color objectColour()
		{
			return CUSTOM;
		}

		@Override
		public Color otherColour()
		{
			return CUSTOM;
		}
	};

	@Test
	public void ordinaryNativeTargetColoursRemainCustomisable()
	{
		assertTrue(hasColour(render("<col=ffff00>Guard", MenuRenderer.TargetKind.NPC, CONFIG), CUSTOM));
		assertTrue(hasColour(render("<col=ffffff>Player", MenuRenderer.TargetKind.PLAYER, CONFIG), CUSTOM));
		assertTrue(hasColour(render("<col=ff9040>Item", MenuRenderer.TargetKind.ITEM, CONFIG), CUSTOM));
		assertTrue(hasColour(render("<col=00ffff>Door", MenuRenderer.TargetKind.OBJECT, CONFIG), CUSTOM));
		assertTrue(hasColour(render("Plain item", MenuRenderer.TargetKind.ITEM, CONFIG), CUSTOM));
	}

	@Test
	public void vanillaGroundAndInventoryPrayerPotionsUseCustomItemColour()
	{
		for (MenuRenderer.TargetKind kind : new MenuRenderer.TargetKind[]{
			kindForAction(MenuAction.GROUND_ITEM_FIRST_OPTION, -1),
			kindForAction(MenuAction.CC_OP, 2434)})
		{
			BufferedImage image = render("<col=ff9040>Prayer potion(4)", kind, CONFIG);
			assertTrue(hasColour(image, CUSTOM));
			assertFalse(hasColour(image, new Color(0xFF9040)));
		}
	}

	@Test
	public void preservesSingleRecolouredGroundItemLikeLootFilters()
	{
		// Loot Filters replaces a ground-item target with a newly wrapped colour span.
		BufferedImage image = render("<col=39c949>Divine super combat potion(4)</col>",
			kindForAction(MenuAction.GROUND_ITEM_FIRST_OPTION, -1), CONFIG);
		assertTrue(hasColour(image, LOOT_FILTER));
		assertFalse(hasColour(image, CUSTOM));
	}

	@Test
	public void preservesAppendedIdSpanLikeIdentificator()
	{
		// Identificator appends a coloured ID span to the original menu target.
		BufferedImage image = render("<col=ff9040>Item<col=dfab23> (ID: 23685)</col>",
			kindForAction(MenuAction.GROUND_ITEM_FIRST_OPTION, -1), CONFIG);
		assertTrue(hasColour(image, CUSTOM));
		assertTrue(hasColour(image, ID_SUFFIX));
		assertFalse(hasColour(image, new Color(0xFF9040)));
	}

	@Test
	public void preservesLaterSpanEvenWhenItUsesTheNativeColour()
	{
		BufferedImage image = render("<col=ff9040>Item<col=ff9040> (ID: 23685)",
			MenuRenderer.TargetKind.ITEM, CONFIG);
		assertTrue(hasColour(image, CUSTOM));
		assertTrue(hasColour(image, new Color(0xFF9040)));
	}

	@Test
	public void preservesFirstTagWhenAppendedAfterUncolouredText()
	{
		BufferedImage image = render("Item<col=ff9040> (ID: 23685)",
			MenuRenderer.TargetKind.ITEM, CONFIG);
		assertTrue(hasColour(image, CUSTOM));
		assertTrue(hasColour(image, new Color(0xFF9040)));
	}

	@Test
	public void preservesExplicitColourOnPluginAddedOtherEntry()
	{
		BufferedImage image = render("<col=39c949>Plugin action", MenuRenderer.TargetKind.OTHER, CONFIG);
		assertTrue(hasColour(image, LOOT_FILTER));
		assertFalse(hasColour(image, CUSTOM));
	}

	@Test
	public void combatLevelFormattingStaysIndependentOfTargetColour()
	{
		Color levelColour = new Color(0x25B45C);
		BufferedImage image = render("<col=ffff00>Guard</col> <col=25b45c>(level-21)</col>",
			MenuRenderer.TargetKind.NPC, CONFIG);
		assertTrue(hasColour(image, CUSTOM));
		assertTrue(hasColour(image, levelColour));
	}

	@Test
	public void gameModeKeepsOriginalTargetColour()
	{
		BufferedImage image = render("<col=ff9040>Item", MenuRenderer.TargetKind.ITEM,
			new RightClickStylerConfig() {});
		assertTrue(hasColour(image, new Color(0xFF9040)));
		assertFalse(hasColour(image, CUSTOM));
	}

	private static BufferedImage render(String target, MenuRenderer.TargetKind kind,
		RightClickStylerConfig config)
	{
		Theme theme = BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).withFont(FontChoice.SANS)
			.withBg(Color.BLACK).withShowHeader(false).withShowBorder(false)
			.withShowShadow(false).withTextShadow(false).withOpacity(100);
		BufferedImage image = new BufferedImage(400, 50, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, config,
				Collections.singletonList(new MenuRenderer.Row("", target, kind)),
				new Rectangle(0, 0, 390, 40), -1, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static MenuRenderer.TargetKind kindForAction(MenuAction action, int itemId)
	{
		MenuEntry entry = (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(),
			new Class<?>[]{MenuEntry.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getNpc":
					case "getPlayer": return null;
					case "getType": return action;
					case "getItemId": return itemId;
					default: throw new UnsupportedOperationException(method.getName());
				}
			});
		return MenuOverlay.classify(entry);
	}

	private static boolean hasColour(BufferedImage image, Color colour)
	{
		for (int y = 19; y < 34; y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (image.getRGB(x, y) == colour.getRGB())
				{
					return true;
				}
			}
		}
		return false;
	}
}
