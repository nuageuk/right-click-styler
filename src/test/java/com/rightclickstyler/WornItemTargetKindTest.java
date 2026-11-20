package com.rightclickstyler;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Worn equipment ops belong to the equipment tab's slot layer, which has no item of its own
 * ({@code getItemId() == -1}); the worn item is the slot's child 1. They must still be Items.
 */
public class WornItemTargetKindTest
{
	private static final int MEDALLION = 22400;
	private static final String TARGET = "<col=ff9040>Drakan's medallion";
	private static final String PLAIN_TARGET = "Drakan's medallion";
	private static final Color CUSTOM_ITEM = new Color(0x00C832);
	private static final Color CUSTOM_OTHER = new Color(0x3300FF);
	private static final Color NATIVE_ITEM = new Color(0xFF9040);
	private static final Color THIRD_PARTY = new Color(0x39C949);

	private static final int[] WORN_SLOTS = {
		InterfaceID.Wornitems.SLOT0, InterfaceID.Wornitems.SLOT1, InterfaceID.Wornitems.SLOT2,
		InterfaceID.Wornitems.SLOT3, InterfaceID.Wornitems.SLOT4, InterfaceID.Wornitems.SLOT5,
		InterfaceID.Wornitems.SLOT7, InterfaceID.Wornitems.SLOT9, InterfaceID.Wornitems.SLOT10,
		InterfaceID.Wornitems.SLOT12, InterfaceID.Wornitems.SLOT13};

	/** An entry given this widget fails the test if its widget is ever looked up. */
	private static final Widget NOT_LOOKED_UP = widget(-1, -1);

	@Test
	public void wornRemoveTeleportAndExamineAreItems()
	{
		Menu destinations = menu(180, 20, 100, 49, entry(MenuAction.CC_OP, -1, null, "Darkmeyer", TARGET));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(worn(MenuAction.CC_OP, "Remove")));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(worn(MenuAction.CC_OP, "Ver Sinhaza")));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(
			entry(MenuAction.CC_OP, -1, wornSlot(InterfaceID.Wornitems.SLOT2, MEDALLION), "Teleport", TARGET,
				destinations)));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(worn(MenuAction.CC_OP_LOW_PRIORITY, "Examine")));
	}

	@Test
	public void everyWornEquipmentSlotIsRecognised()
	{
		for (int slot : WORN_SLOTS)
		{
			assertTrue(Integer.toString(slot), MenuOverlay.isWornEquipmentSlot(slot));
			MenuEntry remove = entry(MenuAction.CC_OP, -1, wornSlot(slot, 4151), "Remove",
				"<col=ff9040>Abyssal whip");
			assertEquals(4151, MenuOverlay.heldItemId(remove));
			assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(remove));
		}
	}

	@Test
	public void emptyOrMissingEquipmentWidgetsAreNotItems()
	{
		int slot = InterfaceID.Wornitems.SLOT2;
		MenuEntry[] entries = {
			entry(MenuAction.CC_OP, -1, null, "Remove", TARGET),
			entry(MenuAction.CC_OP, -1, widget(slot, -1), "Remove", TARGET),
			entry(MenuAction.CC_OP, -1, wornSlot(slot, -1), "Remove", TARGET),
			entry(MenuAction.CC_OP, -1, widget(slot, -1, widget(slot, MEDALLION)), "Remove", TARGET),
			entry(MenuAction.CC_OP, -1, widget(slot, -1, widget(slot, -1), null), "Remove", TARGET),
			entry(MenuAction.CC_OP, -1, widget(slot, MEDALLION), "Remove", TARGET)};
		for (int i = 0; i < entries.length; i++)
		{
			assertEquals("case " + i, -1, MenuOverlay.heldItemId(entries[i]));
			assertEquals("case " + i, MenuRenderer.TargetKind.OTHER, MenuOverlay.classify(entries[i]));
		}
	}

	@Test
	public void unrelatedWidgetActionsStayOther()
	{
		// Each holds an item in child 1, so only the component's identity keeps it Other.
		int[] components = {
			InterfaceID.Wornitems.UNIVERSE, InterfaceID.Wornitems.EQUIPMENT, InterfaceID.Wornitems.EQUIPMENT_ICON,
			InterfaceID.Wornitems.PRICECHECKER, InterfaceID.Wornitems.DEATHKEEP, InterfaceID.Wornitems.CALL_FOLLOWER,
			InterfaceID.Wornitems.EXTRA_AMMO_LINK, InterfaceID.Wornitems.EXTRA_QUIVER_SLOT,
			InterfaceID.Wornitems.EXTRA_AMMO_SLOT, InterfaceID.Wornitems.DEADMAN_SIGILS,
			InterfaceID.Wornitems.SLOT0 - 1, InterfaceID.Wornitems.SLOT13 + 1,
			InterfaceID.Prayerbook.PRAYER1, InterfaceID.MagicSpellbook.TELEPORT_HOME_STANDARD,
			InterfaceID.Inventory.ITEMS, InterfaceID.Equipment.SLOT0, InterfaceID.Bankmain.WORNSLOT0};
		for (int component : components)
		{
			for (MenuAction type : new MenuAction[]{MenuAction.CC_OP, MenuAction.CC_OP_LOW_PRIORITY})
			{
				MenuEntry entry = entry(type, -1, wornSlot(component, MEDALLION), "Activate", TARGET);
				assertEquals(Integer.toString(component), -1, MenuOverlay.heldItemId(entry));
				assertEquals(Integer.toString(component), MenuRenderer.TargetKind.OTHER, MenuOverlay.classify(entry));
			}
		}
		for (MenuAction type : new MenuAction[]{MenuAction.WIDGET_TARGET, MenuAction.WIDGET_TARGET_ON_WIDGET,
			MenuAction.WIDGET_CONTINUE, MenuAction.RUNELITE_WIDGET, MenuAction.RUNELITE})
		{
			assertEquals(type.name(), MenuRenderer.TargetKind.OTHER,
				MenuOverlay.classify(entry(type, -1, NOT_LOOKED_UP, "Cast", TARGET)));
		}
	}

	@Test
	public void otherClassificationsAreUnchanged()
	{
		assertEquals("inventory", MenuRenderer.TargetKind.ITEM,
			MenuOverlay.classify(entry(MenuAction.CC_OP, MEDALLION, NOT_LOOKED_UP, "Wear", TARGET)));
		assertEquals("inventory examine", MenuRenderer.TargetKind.ITEM,
			MenuOverlay.classify(entry(MenuAction.CC_OP_LOW_PRIORITY, MEDALLION, NOT_LOOKED_UP, "Examine", TARGET)));
		assertEquals("ground", MenuRenderer.TargetKind.ITEM,
			MenuOverlay.classify(entry(MenuAction.GROUND_ITEM_THIRD_OPTION, -1, NOT_LOOKED_UP, "Take", TARGET)));
		assertEquals("npc", MenuRenderer.TargetKind.NPC, MenuOverlay.classify(entry(MenuAction.NPC_FIRST_OPTION, -1,
			NOT_LOOKED_UP, "Talk-to", "<col=ffff00>Guard", null, stub(NPC.class), null)));
		assertEquals("player", MenuRenderer.TargetKind.PLAYER, MenuOverlay.classify(entry(MenuAction.PLAYER_SECOND_OPTION,
			-1, NOT_LOOKED_UP, "Follow", "<col=ffffff>Zezima", null, null, stub(Player.class))));
		assertEquals("object", MenuRenderer.TargetKind.OBJECT,
			MenuOverlay.classify(entry(MenuAction.GAME_OBJECT_FIRST_OPTION, -1, NOT_LOOKED_UP, "Open", "<col=00ffff>Door")));
		assertEquals("walk", MenuRenderer.TargetKind.OTHER,
			MenuOverlay.classify(entry(MenuAction.WALK, -1, NOT_LOOKED_UP, "Walk here", "")));
	}

	@Test
	public void menuEntrySwapperSubmenuOnAWornItemIsAnItem()
	{
		// Menu Entry Swapper copies the worn Examine target into a RuneLite submenu parent.
		Menu swaps = menu(180, 20, 140, 64,
			entry(MenuAction.RUNELITE, -1, NOT_LOOKED_UP, "Reset", ""),
			entry(MenuAction.RUNELITE, -1, NOT_LOOKED_UP, "Remove", TARGET),
			entry(MenuAction.RUNELITE, -1, NOT_LOOKED_UP, "Teleport", ""));
		MenuEntry swap = entry(MenuAction.RUNELITE, -1, NOT_LOOKED_UP, "Swap left-click", TARGET, swaps);
		Menu root = menu(10, 10, 160, 79,
			entry(MenuAction.CANCEL, -1, NOT_LOOKED_UP, "Cancel", ""),
			worn(MenuAction.CC_OP_LOW_PRIORITY, "Examine"),
			swap,
			worn(MenuAction.CC_OP, "Remove"));
		List<Filled> filled = fill(SubmenuTree.capture(root));

		List<MenuRenderer.Row> rootRows = filled.get(0).rows;
		assertEquals("Swap left-click", rootRows.get(1).option());
		assertEquals(MenuRenderer.TargetKind.ITEM, rootRows.get(0).kind());
		assertEquals(MenuRenderer.TargetKind.ITEM, rootRows.get(1).kind());
		assertEquals(MenuRenderer.TargetKind.ITEM, rootRows.get(2).kind());
		assertEquals(MenuRenderer.TargetKind.OTHER, rootRows.get(3).kind());

		Filled child = filled.get(1);
		assertEquals(TARGET, filled.get(1).node.header);
		assertEquals(MenuRenderer.TargetKind.ITEM, child.headerKind);
		assertEquals("Teleport", child.rows.get(0).option());
		assertEquals("no target of its own", MenuRenderer.TargetKind.OTHER, child.rows.get(0).kind());
		assertEquals("repeats the worn item", MenuRenderer.TargetKind.ITEM, child.rows.get(1).kind());
		assertEquals(MenuRenderer.TargetKind.OTHER, child.rows.get(2).kind());

		// Without a worn item behind the same target, the parent stays Other.
		Menu unowned = menu(10, 10, 160, 49,
			entry(MenuAction.CC_OP, -1, wornSlot(InterfaceID.Wornitems.EQUIPMENT, MEDALLION), "View", TARGET),
			entry(MenuAction.RUNELITE, -1, NOT_LOOKED_UP, "Swap left-click", TARGET, swaps));
		List<Filled> other = fill(SubmenuTree.capture(unowned));
		assertEquals(MenuRenderer.TargetKind.OTHER, other.get(0).rows.get(0).kind());
		assertEquals(MenuRenderer.TargetKind.OTHER, other.get(1).headerKind);
	}

	@Test
	public void gameCreatedWornSubmenuChildrenInheritTheItemKind()
	{
		// Bottom-up, as the client stores them. The destinations carry no item of their own.
		Menu destinations = menu(180, 20, 160, 79,
			entry(MenuAction.CC_OP, -1, null, "Slepe", TARGET + "<col=dfab23> (ID: 22400)"),
			entry(MenuAction.CC_OP, -1, null, "Darkmeyer", TARGET),
			entry(MenuAction.CC_OP, -1, null, "Plugin", "<col=ffff00>Unrelated"),
			entry(MenuAction.CC_OP, -1, null, "Ver Sinhaza", ""));
		Menu root = menu(10, 10, 160, 64,
			entry(MenuAction.CANCEL, -1, NOT_LOOKED_UP, "Cancel", ""),
			worn(MenuAction.CC_OP_LOW_PRIORITY, "Examine"),
			entry(MenuAction.CC_OP, -1, wornSlot(InterfaceID.Wornitems.SLOT2, MEDALLION), "Teleport", TARGET,
				destinations));
		List<Filled> filled = fill(SubmenuTree.capture(root));

		assertEquals("Teleport", filled.get(0).rows.get(0).option());
		assertEquals(MenuRenderer.TargetKind.ITEM, filled.get(0).rows.get(0).kind());
		Filled child = filled.get(1);
		assertEquals("the header repeats the worn item", MenuRenderer.TargetKind.ITEM, child.headerKind);
		assertEquals("Ver Sinhaza", child.rows.get(0).option());
		assertEquals(MenuRenderer.TargetKind.OTHER, child.rows.get(0).kind());
		assertEquals("unrelated targets keep their own kind", MenuRenderer.TargetKind.OTHER, child.rows.get(1).kind());
		assertEquals(MenuRenderer.TargetKind.ITEM, child.rows.get(2).kind());
		assertEquals("an appended ID suffix still names the item", MenuRenderer.TargetKind.ITEM,
			child.rows.get(3).kind());
		assertEquals(PLAIN_TARGET, MenuRenderer.TargetKind.ITEM, MenuOverlay.childKind(
			entry(MenuAction.CC_OP, -1, null, "Darkmeyer", TARGET), MenuRenderer.TargetKind.ITEM, PLAIN_TARGET));
	}

	@Test
	public void wornTargetsTakeTheCustomItemColourAndKeepGameAndTagColours()
	{
		MenuRenderer.TargetKind worn = MenuOverlay.classify(worn(MenuAction.CC_OP, "Remove"));
		BufferedImage custom = render("Remove", TARGET, worn, TargetMode.CUSTOM);
		assertTrue(hasColour(custom, CUSTOM_ITEM));
		assertFalse(hasColour(custom, NATIVE_ITEM));
		assertFalse(hasColour(custom, CUSTOM_OTHER));

		BufferedImage game = render("Remove", TARGET, worn, TargetMode.GAME);
		assertTrue("Game mode keeps the native item colour", hasColour(game, NATIVE_ITEM));
		assertFalse(hasColour(game, CUSTOM_ITEM));

		BufferedImage tagged = render("Remove", "<col=39c949>Drakan's medallion", worn, TargetMode.CUSTOM);
		assertTrue("an intentional third-party colour wins", hasColour(tagged, THIRD_PARTY));
		assertFalse(hasColour(tagged, CUSTOM_ITEM));

		for (MenuEntry item : new MenuEntry[]{entry(MenuAction.CC_OP, MEDALLION, NOT_LOOKED_UP, "Wear", TARGET),
			entry(MenuAction.GROUND_ITEM_THIRD_OPTION, -1, NOT_LOOKED_UP, "Take", TARGET)})
		{
			BufferedImage image = render(item.getOption(), TARGET, MenuOverlay.classify(item), TargetMode.CUSTOM);
			assertTrue(item.getOption(), hasColour(image, CUSTOM_ITEM));
			assertFalse(item.getOption(), hasColour(image, NATIVE_ITEM));
		}
	}

	// ---- fixtures

	private static final class Filled
	{
		SubmenuTree.Node node;
		final List<MenuRenderer.Row> rows = new ArrayList<>();
		MenuRenderer.TargetKind headerKind;
	}

	/** Fills every node in tree order, as the submenu renderer does along the open chain. */
	private static List<Filled> fill(SubmenuTree tree)
	{
		List<Filled> filled = new ArrayList<>();
		for (SubmenuTree.Node node : tree.nodes)
		{
			Filled menu = new Filled();
			menu.node = node;
			MenuRenderer.Row parentRow = node.parentNode < 0 ? null : MenuOverlay.openingRow(tree, node,
				tree.nodes.get(node.parentNode), filled.get(node.parentNode).rows);
			menu.headerKind = MenuOverlay.fillMenuRows(node, parentRow, menu.rows, new ArrayList<>());
			filled.add(menu);
		}
		return filled;
	}

	private static MenuEntry worn(MenuAction type, String option)
	{
		return entry(type, -1, wornSlot(InterfaceID.Wornitems.SLOT2, MEDALLION), option, TARGET);
	}

	/** The worn slot layer as the equipment tab builds it: [0] background graphic, [1] the item. */
	private static Widget wornSlot(int componentId, int itemId)
	{
		return widget(componentId, -1, widget(componentId, -1), widget(componentId, itemId));
	}

	private static Widget widget(int id, int itemId, Widget... children)
	{
		return (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getId":
						return id;
					case "getItemId":
						return itemId;
					case "getChild":
						int index = (Integer) args[0];
						return index >= 0 && index < children.length ? children[index] : null;
					default:
						return identity(proxy, method.getName(), args);
				}
			});
	}

	private static MenuEntry entry(MenuAction type, int itemId, Widget widget, String option, String target)
	{
		return entry(type, itemId, widget, option, target, null);
	}

	private static MenuEntry entry(MenuAction type, int itemId, Widget widget, String option, String target,
		Menu subMenu)
	{
		return entry(type, itemId, widget, option, target, subMenu, null, null);
	}

	private static MenuEntry entry(MenuAction type, int itemId, Widget widget, String option, String target,
		Menu subMenu, NPC npc, Player player)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getType":
						return type;
					case "getItemId":
						return itemId;
					case "getOption":
						return option;
					case "getTarget":
						return target;
					case "getSubMenu":
						return subMenu;
					case "getNpc":
						return npc;
					case "getPlayer":
						return player;
					case "getWidget":
						if (widget == NOT_LOOKED_UP)
						{
							throw new AssertionError("the widget of '" + option + "' must not be looked up");
						}
						return widget;
					default:
						return identity(proxy, method.getName(), args);
				}
			});
	}

	private static Menu menu(int x, int y, int width, int height, MenuEntry... entries)
	{
		return (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[]{Menu.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getMenuX":
						return x;
					case "getMenuY":
						return y;
					case "getMenuWidth":
						return width;
					case "getMenuHeight":
						return height;
					case "getMenuEntries":
						return entries;
					default:
						return identity(proxy, method.getName(), args);
				}
			});
	}

	private static <T> T stub(Class<T> type)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(proxy, method, args) -> identity(proxy, method.getName(), args)));
	}

	private static Object identity(Object proxy, String method, Object[] args)
	{
		switch (method)
		{
			case "equals":
				return proxy == args[0];
			case "hashCode":
				return System.identityHashCode(proxy);
			case "toString":
				return "stub@" + Integer.toHexString(System.identityHashCode(proxy));
			default:
				throw new UnsupportedOperationException(method);
		}
	}

	private static RightClickStylerConfig config(TargetMode mode)
	{
		return new RightClickStylerConfig()
		{
			@Override
			public TargetMode targetMode()
			{
				return mode;
			}

			@Override
			public Color itemColour()
			{
				return CUSTOM_ITEM;
			}

			@Override
			public Color otherColour()
			{
				return CUSTOM_OTHER;
			}
		};
	}

	private static BufferedImage render(String option, String target, MenuRenderer.TargetKind kind, TargetMode mode)
	{
		Theme theme = BuiltInThemes.find("dark").withFont(FontChoice.RS_BOLD).withFontSize(12)
			.withBg(Color.BLACK).withShowHeader(false).withShowBorder(false)
			.withShowShadow(false).withTextShadow(false).withOpacity(100);
		BufferedImage image = new BufferedImage(400, 50, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, config(mode),
				Collections.singletonList(new MenuRenderer.Row(option, target, kind)),
				new Rectangle(0, 0, 390, 40), -1, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static boolean hasColour(BufferedImage image, Color colour)
	{
		int rgb = colour.getRGB() & 0xFFFFFF;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == rgb)
				{
					return true;
				}
			}
		}
		return false;
	}
}
