package com.rightclickstyler;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuOverlayTest
{
	@Test
	public void usesCompleteNativeMenuBounds()
	{
		Rectangle bounds = new Rectangle();

		MenuOverlay.setNativeBounds(bounds, new StubMenu(12, 34, 207, 79));

		assertEquals(new Rectangle(12, 34, 207, 79), bounds);
	}

	@Test
	public void fallsBackBeforeInspectingScrollableMenus()
	{
		assertTrue(MenuOverlay.canInspectMenu(false, true, false));
		assertFalse(MenuOverlay.canInspectMenu(false, true, true));
		assertFalse(MenuOverlay.canInspectMenu(true, true, false));
		assertFalse(MenuOverlay.canInspectMenu(false, false, false));
	}

	@Test
	public void detectsSubmenus()
	{
		MenuEntry plain = new StubMenuEntry(MenuAction.CANCEL, -1, null);
		MenuEntry submenu = new StubMenuEntry(MenuAction.PLAYER_FIRST_OPTION, -1,
			new StubMenu(0, 0, 0, 0));

		assertFalse(MenuOverlay.containsSubMenu(new MenuEntry[]{plain}));
		assertTrue(MenuOverlay.containsSubMenu(new MenuEntry[]{plain, submenu}));
	}

	@Test
	public void bankLayoutPotionDoseMenuUsesTheNativeSubmenuPath()
	{
		// Bank Tags' layout creates a Doses sub-op for potion-storage-backed items.
		StubMenu root = new StubMenu(10, 10, 150, 49);
		StubMenu doses = new StubMenu(160, 25, 95, 49);
		doses.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry("1 Dose", null), new StubMenuEntry("4 Dose", null)});
		MenuEntry withdraw = new StubMenuEntry(MenuAction.CC_OP, 2434, null)
			.setOption("Withdraw-1").setTarget("<col=ff9040>Prayer potion(4)");
		MenuEntry doseParent = new StubMenuEntry(MenuAction.CC_OP, 2434, doses)
			.setOption("Doses").setTarget("<col=ff9040>Prayer potion(4)");
		root.setMenuEntries(new MenuEntry[]{withdraw, doseParent});

		assertTrue(MenuOverlay.containsSubMenu(root.getMenuEntries()));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(withdraw));
		assertEquals(MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(doseParent));
		SubmenuTree tree = SubmenuTree.capture(root);
		assertEquals(2, tree.nodes.size());
		assertEquals("<col=ff9040>Prayer potion(4)", tree.nodes.get(1).header);
		assertEquals(doses, tree.nodes.get(1).menu);
	}

	// ---- Submenu target-colour inheritance (Drakan's medallion -> Teleport) ----

	private static final Color CUSTOM_ITEM = new Color(0x3300FF);
	private static final Color CUSTOM_OTHER = new Color(0x00AA00);
	private static final Color NATIVE_ITEM = new Color(0xFF9040);

	private static RightClickStylerConfig customItemConfig()
	{
		return new RightClickStylerConfig()
		{
			@Override
			public TargetMode targetMode()
			{
				return TargetMode.CUSTOM;
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

	/** Root: Teleport on an inventory medallion opens a child of destinations. */
	private static StubMenu medallionMenu(StubMenu child, String parentTarget)
	{
		StubMenu root = new StubMenu(10, 10, 160, 49);
		MenuEntry wear = new StubMenuEntry(MenuAction.CC_OP, 22400, null)
			.setOption("Wear").setTarget("<col=ff9040>Drakan's medallion");
		MenuEntry teleport = new StubMenuEntry(MenuAction.CC_OP, 22400, child)
			.setOption("Teleport").setTarget(parentTarget);
		root.setMenuEntries(new MenuEntry[]{wear, teleport});
		return root;
	}

	private static final class FilledMenu
	{
		final List<MenuRenderer.Row> rows = new ArrayList<>();
		final List<MenuRenderer.Row> pool = new ArrayList<>();
		MenuRenderer.TargetKind headerKind;
	}

	/** Fills every node in tree order, as the submenu renderer does along the open chain. */
	private static List<FilledMenu> fill(SubmenuTree tree)
	{
		List<FilledMenu> filled = new ArrayList<>();
		for (SubmenuTree.Node node : tree.nodes)
		{
			FilledMenu menu = new FilledMenu();
			MenuRenderer.Row parentRow = node.parentNode < 0 ? null : MenuOverlay.openingRow(tree, node,
				tree.nodes.get(node.parentNode), filled.get(node.parentNode).rows);
			menu.headerKind = MenuOverlay.fillMenuRows(node, parentRow, menu.rows, menu.pool);
			filled.add(menu);
		}
		return filled;
	}

	@Test
	public void itemSubmenuHeaderAndRepeatedTargetsInheritTheItemKind()
	{
		StubMenu child = new StubMenu(170, 25, 160, 79);
		// Stored bottom-up, as the client stores them.
		child.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Cancel"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Plugin")
				.setTarget("<col=ffff00>Unrelated"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Slepe")
				.setTarget("<col=ff9040>Drakan's medallion<col=dfab23> (ID: 22400)"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Darkmeyer")
				.setTarget("<col=ff9040>Drakan's medallion"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Ver Sinhaza")});
		SubmenuTree tree = SubmenuTree.capture(medallionMenu(child, "<col=ff9040>Drakan's medallion"));
		List<FilledMenu> filled = fill(tree);

		assertEquals("the root has no opening row", null, filled.get(0).headerKind);
		assertEquals(MenuRenderer.TargetKind.ITEM, filled.get(0).rows.get(0).kind());
		assertEquals("the child header repeats the item target",
			MenuRenderer.TargetKind.ITEM, filled.get(1).headerKind);
		List<MenuRenderer.Row> rows = filled.get(1).rows;
		assertEquals("Ver Sinhaza", rows.get(0).option());
		assertEquals("no target of its own", MenuRenderer.TargetKind.OTHER, rows.get(0).kind());
		assertEquals(MenuRenderer.TargetKind.ITEM, rows.get(1).kind());
		assertEquals("an appended ID suffix still names the item", MenuRenderer.TargetKind.ITEM, rows.get(2).kind());
		assertEquals("unrelated child targets keep their own kind", MenuRenderer.TargetKind.OTHER, rows.get(3).kind());
		assertEquals(MenuRenderer.TargetKind.OTHER, rows.get(4).kind());
	}

	@Test
	public void childTargetsOnlyInheritWhenTheyRepeatTheParentTarget()
	{
		String parent = "Drakan's medallion";
		MenuRenderer.TargetKind item = MenuRenderer.TargetKind.ITEM;
		assertEquals(item, MenuOverlay.childKind(new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setTarget("<col=ff9040>Drakan's medallion</col>"), item, parent));
		assertEquals(MenuRenderer.TargetKind.OTHER, MenuOverlay.childKind(new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setTarget("<col=ff9040>Drakan's medallions"), item, parent));
		assertEquals(MenuRenderer.TargetKind.OTHER, MenuOverlay.childKind(new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setTarget("Use Drakan's medallion"), item, parent));
		assertEquals("no inheritance without a parent kind", MenuRenderer.TargetKind.OTHER,
			MenuOverlay.childKind(new StubMenuEntry(MenuAction.CC_OP, -1, null)
				.setTarget("<col=ff9040>Drakan's medallion"), null, parent));
		assertEquals("a child's own classification always wins", MenuRenderer.TargetKind.OBJECT,
			MenuOverlay.childKind(new StubMenuEntry(MenuAction.GAME_OBJECT_FIRST_OPTION, -1, null)
				.setTarget("<col=ff9040>Drakan's medallion"), item, parent));
	}

	@Test
	public void optionDerivedHeadersAndOtherParentsAreNotTargetColoured()
	{
		StubMenu child = new StubMenu(170, 25, 160, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setOption("Ver Sinhaza").setTarget("Teleport")});
		List<FilledMenu> blankTarget = fill(SubmenuTree.capture(medallionMenu(child, "")));
		assertEquals("header is the parent option, not a target", null, blankTarget.get(1).headerKind);

		StubMenu otherChild = new StubMenu(170, 25, 160, 34);
		otherChild.setMenuEntries(new MenuEntry[]{new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setOption("One").setTarget("<col=ffff00>Plugin")});
		StubMenu root = new StubMenu(10, 10, 160, 34);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry(MenuAction.RUNELITE, -1, otherChild)
			.setOption("Configure").setTarget("<col=ffff00>Plugin")});
		List<FilledMenu> other = fill(SubmenuTree.capture(root));
		assertEquals(MenuRenderer.TargetKind.OTHER, other.get(1).headerKind);
		assertEquals("OTHER parents pass nothing down", MenuRenderer.TargetKind.OTHER,
			other.get(1).rows.get(0).kind());
	}

	@Test
	public void nestedSubmenusInheritThroughAnInheritedRow()
	{
		StubMenu grandchild = new StubMenu(340, 40, 120, 34);
		grandchild.setMenuEntries(new MenuEntry[]{new StubMenuEntry(MenuAction.CC_OP, -1, null)
			.setOption("Confirm").setTarget("<col=ff9040>Drakan's medallion")});
		StubMenu child = new StubMenu(170, 25, 160, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry(MenuAction.CC_OP, -1, grandchild)
			.setOption("Darkmeyer").setTarget("<col=ff9040>Drakan's medallion")});
		List<FilledMenu> filled = fill(SubmenuTree.capture(medallionMenu(child, "<col=ff9040>Drakan's medallion")));
		assertEquals(3, filled.size());
		assertEquals(MenuRenderer.TargetKind.ITEM, filled.get(1).rows.get(0).kind());
		assertEquals(MenuRenderer.TargetKind.ITEM, filled.get(2).headerKind);
		assertEquals(MenuRenderer.TargetKind.ITEM, filled.get(2).rows.get(0).kind());
	}

	@Test
	public void itemSubmenuRendersParentAndChildItemTextInTheCustomItemColour()
	{
		StubMenu child = new StubMenu(10, 60, 300, 64);
		child.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Plugin")
				.setTarget("<col=39c949>Tagged"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Darkmeyer")
				.setTarget("<col=ff9040>Drakan's medallion"),
			new StubMenuEntry(MenuAction.CC_OP, -1, null).setOption("Ver Sinhaza")});
		SubmenuTree tree = SubmenuTree.capture(medallionMenu(child, "<col=ff9040>Drakan's medallion"));
		List<FilledMenu> filled = fill(tree);
		Theme theme = BuiltInThemes.find(BuiltInThemes.DEFAULT_ID).withFont(FontChoice.SANS)
			.withBg(Color.BLACK).withHeader(Color.BLACK).withShowHeader(true).withShowBorder(false)
			.withShowShadow(false).withTextShadow(false).withOpacity(100);
		Rectangle rootBounds = new Rectangle(10, 0, 300, 49);
		Rectangle childBounds = new Rectangle(10, 60, 300, 64);

		for (boolean custom : new boolean[]{true, false})
		{
			RightClickStylerConfig config = custom ? customItemConfig() : new RightClickStylerConfig() { };
			BufferedImage image = new BufferedImage(320, 130, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			MenuRenderer renderer = new MenuRenderer();
			try
			{
				renderer.render(g, null, theme, config, filled.get(0).rows, rootBounds, -1, 0,
					null, filled.get(0).headerKind, 0);
				renderer.render(g, null, theme, config, filled.get(1).rows, childBounds, -1, 0,
					tree.nodes.get(1).header, filled.get(1).headerKind, 0);
			}
			finally
			{
				g.dispose();
			}
			Color item = custom ? CUSTOM_ITEM : NATIVE_ITEM;
			Color absent = custom ? NATIVE_ITEM : CUSTOM_ITEM;
			String mode = custom ? "custom: " : "game: ";
			Rectangle rootRows = new Rectangle(10, 19, 300, 30);
			Rectangle childHeader = new Rectangle(10, 60, 300, 19);
			Rectangle childItemRow = new Rectangle(10, 79 + 15, 300, 15);
			Rectangle childTaggedRow = new Rectangle(10, 79 + 30, 300, 15);
			assertTrue(mode + "parent item rows", containsColour(image, rootRows, item));
			assertFalse(mode + "parent item rows", containsColour(image, rootRows, absent));
			assertTrue(mode + "child header", containsColour(image, childHeader, item));
			assertFalse(mode + "child header", containsColour(image, childHeader, absent));
			assertTrue(mode + "child item target", containsColour(image, childItemRow, item));
			assertFalse(mode + "child item target", containsColour(image, childItemRow, absent));
			assertTrue(mode + "explicit tag kept", containsColour(image, childTaggedRow, new Color(0x39C949)));
			assertFalse(mode + "explicit tag kept", containsColour(image, childTaggedRow, CUSTOM_OTHER));
		}
	}

	private static boolean containsColour(BufferedImage image, Rectangle area, Color colour)
	{
		for (int y = area.y; y < area.y + area.height; y++)
		{
			for (int x = area.x; x < area.x + area.width; x++)
			{
				if (image.getRGB(x, y) == colour.getRGB())
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void probeReportsOnlyCanvasVisibleCandidateRectangles()
	{
		StubMenu root = new StubMenu(10, 10, 80, 64);
		StubMenu visible = new StubMenu(90, 20, 40, 34);
		StubMenu clipped = new StubMenu(180, 70, 40, 40);
		StubMenu outside = new StubMenu(300, 10, 40, 34);
		for (StubMenu child : new StubMenu[]{visible, clipped, outside})
		{
			child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		}
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Visible", visible),
			new StubMenuEntry("Clipped", clipped), new StubMenuEntry("Outside", outside)});
		int[] pixels = new int[200 * 100];
		NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(
			pixels, 200, 100, SubmenuTree.capture(root),
			() -> paint(pixels, 200, 100, root, visible));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, result.state);
		assertEquals(2, result.visibleCandidates);
		assertEquals(40L * 34 + 20L * 30, result.candidatePixels);
		assertTrue(result.restored);
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	@Test
	public void classifiesEveryGameObjectActionBeforeItemIds()
	{
		MenuAction[] actions = {
			MenuAction.ITEM_USE_ON_GAME_OBJECT,
			MenuAction.WIDGET_TARGET_ON_GAME_OBJECT,
			MenuAction.GAME_OBJECT_FIRST_OPTION,
			MenuAction.GAME_OBJECT_SECOND_OPTION,
			MenuAction.GAME_OBJECT_THIRD_OPTION,
			MenuAction.GAME_OBJECT_FOURTH_OPTION,
			MenuAction.GAME_OBJECT_FIFTH_OPTION,
			MenuAction.EXAMINE_OBJECT
		};

		for (MenuAction action : actions)
		{
			MenuEntry entryWithItemId = new StubMenuEntry(action, 4151, null);
			assertEquals(action.name(), MenuRenderer.TargetKind.OBJECT, MenuOverlay.classify(entryWithItemId));
		}
	}

	@Test
	public void classifiesGroundItemsEvenWithoutAnItemIdField()
	{
		MenuAction[] groundActions = {
			MenuAction.ITEM_USE_ON_GROUND_ITEM,
			MenuAction.WIDGET_TARGET_ON_GROUND_ITEM,
			MenuAction.GROUND_ITEM_FIRST_OPTION,
			MenuAction.GROUND_ITEM_SECOND_OPTION,
			MenuAction.GROUND_ITEM_THIRD_OPTION,
			MenuAction.GROUND_ITEM_FOURTH_OPTION,
			MenuAction.GROUND_ITEM_FIFTH_OPTION,
			MenuAction.EXAMINE_ITEM_GROUND
		};
		for (MenuAction action : groundActions)
		{
			MenuEntry entry = new StubMenuEntry(action, -1, null).setIdentifier(2434);
			assertEquals(action.name(), MenuRenderer.TargetKind.ITEM, MenuOverlay.classify(entry));
		}
		assertEquals(MenuRenderer.TargetKind.ITEM,
			MenuOverlay.classify(new StubMenuEntry(MenuAction.CC_OP, 2434, null)));
		assertEquals(MenuRenderer.TargetKind.OTHER,
			MenuOverlay.classify(new StubMenuEntry(MenuAction.CANCEL, -1, null)));
	}

	@Test
	public void snapshotsSiblingAndNestedMenusInNativeOrder()
	{
		StubMenu root = new StubMenu(10, 10, 40, 64);
		StubMenu first = new StubMenu(50, 15, 38, 49);
		StubMenu nested = new StubMenu(88, 20, 35, 34);
		StubMenu second = new StubMenu(50, 65, 39, 34);
		first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Nested", nested)
			.setTarget("<col=ff8800>Nested item</col>")});
		nested.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		second.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Other", null)});
		root.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry("First", first).setTarget("<col=00ff00>First item</col>"),
			new StubMenuEntry("Second", second)});
		SubmenuTree tree = SubmenuTree.capture(root);
		assertEquals(4, tree.nodes.size());
		assertEquals(Arrays.asList(-1, 0, -1), tree.candidateParents);
		assertEquals("<col=00ff00>First item</col>", tree.nodes.get(1).header);
		assertEquals("<col=ff8800>Nested item</col>", tree.nodes.get(2).header);
		assertEquals("Second", tree.nodes.get(3).header);
		assertEquals(new Rectangle(50, 65, 39, 34), tree.nodes.get(3).bounds);

		int[] pixels = new int[130 * 110];
		int[] original = pixels.clone();
		NativeSubmenuProbe.Result nestedResult = NativeSubmenuProbe.probePixels(pixels, 130, 110,
			tree, () -> paint(pixels, 130, 110, root, first, nested));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, nestedResult.state);
		assertTrue(Arrays.equals(new int[]{0, 1}, nestedResult.activeChain));
		assertTrue(Arrays.equals(original, pixels));

		NativeSubmenuProbe.Result sibling = NativeSubmenuProbe.probePixels(pixels, 130, 110,
			tree, () -> paint(pixels, 130, 110, root, second));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, sibling.state);
		assertTrue(Arrays.equals(new int[]{2}, sibling.activeChain));
		assertTrue(Arrays.equals(original, pixels));

		NativeSubmenuProbe.Result stale = NativeSubmenuProbe.probePixels(pixels, 130, 110,
			tree, () -> paint(pixels, 130, 110, root));
		assertEquals(NativeSubmenuProbe.State.NO_CHILD, stale.state);
		assertTrue(Arrays.equals(original, pixels));
	}

	@Test
	public void contextualChildHeadersUseNativeParentTargets()
	{
		String[] options = {"Inventory tag", "Swap left-click", "Swap shift-click"};
		String[] targets = {
			"<col=ff9040>Dragon scimitar</col>",
			"<col=00ff00>Very long item name with formatting</col>",
			"<col=ffff00>Another long item name</col>"
		};
		StubMenu root = new StubMenu(80, 10, 100, 64);
		MenuEntry[] entries = new MenuEntry[options.length];
		for (int i = 0; i < options.length; i++)
		{
			StubMenu child = new StubMenu(10, 15 + i * 15, 70, 34);
			child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
			entries[i] = new StubMenuEntry(options[i], child).setTarget(targets[i]);
		}
		root.setMenuEntries(entries);
		SubmenuTree tree = SubmenuTree.capture(root);
		for (int i = 0; i < options.length; i++)
		{
			assertEquals(targets[i], tree.nodes.get(i + 1).header);
			assertTrue(tree.nodes.get(0).childOpensLeft[i]);
		}
		entries[0].setTarget("");
		assertEquals("Inventory tag", SubmenuTree.capture(root).nodes.get(1).header);
	}

	@Test
	public void ambiguousChildFallsBackAndDrawFailureRestoresPixels()
	{
		StubMenu root = new StubMenu(10, 10, 60, 60);
		StubMenu child = new StubMenu(20, 20, 20, 20);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Child", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Parent", child)});
		SubmenuTree tree = SubmenuTree.capture(root);
		int[] pixels = new int[100 * 100];
		NativeSubmenuProbe.Result ambiguous = NativeSubmenuProbe.probePixels(pixels, 100, 100,
			tree, () -> paint(pixels, 100, 100, root, child));
		assertEquals(NativeSubmenuProbe.State.AMBIGUOUS, ambiguous.state);
		assertTrue(ambiguous.reason.startsWith("ambiguous geometry index=0"));

		StubMenu outside = new StubMenu(70, 20, 20, 20);
		outside.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Child", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Parent", outside)});
		SubmenuTree drawable = SubmenuTree.capture(root);
		int[] original = pixels.clone();
		try
		{
			NativeSubmenuProbe.probePixels(pixels, 100, 100, drawable, () ->
			{
				paint(pixels, 100, 100, root, outside);
				throw new IllegalStateException("synthetic draw failure");
			});
			throw new AssertionError("expected draw exception");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(Arrays.equals(original, pixels));
		}
	}

	@Test
	public void reusesSnapshotAndWitnessBuffersAcrossFrames() throws Exception
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu child = new StubMenu(50, 15, 30, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Parent", child)});
		SubmenuTree tree = SubmenuTree.capture(root);
		int[] pixels = new int[100 * 80];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		probe.samplePixels(pixels, 100, 80, tree, () -> paint(pixels, 100, 80, root, child));
		Object snapshot = privateField(probe, "snapshot");
		Object witnesses = privateField(probe, "exclusivePool");
		NativeSubmenuProbe.Result second = probe.samplePixels(pixels, 100, 80, tree,
			() -> paint(pixels, 100, 80, root, child));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, second.state);
		assertEquals(1, second.drawPasses);
		assertTrue(snapshot == privateField(probe, "snapshot"));
		assertTrue(witnesses == privateField(probe, "exclusivePool"));
		assertTrue(Arrays.equals(new int[8000], pixels));
	}

	@Test
	public void detectsLeftOpeningChildAtCanvasEdge()
	{
		StubMenu root = new StubMenu(80, 10, 35, 49);
		StubMenu child = new StubMenu(45, 15, 35, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Parent", child)});
		SubmenuTree tree = SubmenuTree.capture(root);
		assertTrue(tree.nodes.get(0).childOpensLeft[0]);
		assertEquals(new Rectangle(45, 15, 35, 34), tree.nodes.get(1).bounds);
		int[] pixels = new int[100 * 80];
		NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			tree, () -> paint(pixels, 100, 80, root, child));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, result.state);
		assertTrue(Arrays.equals(new int[8000], pixels));
	}

	@Test
	public void dormantChildrenAllowRootThemeAndSwitchWithoutFallback()
	{
		StubMenu root = new StubMenu(10, 10, 40, 64);
		StubMenu first = new StubMenu(0, 0, 0, 0);
		StubMenu second = new StubMenu(0, 0, 0, 0);
		first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("First action", null)});
		second.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Second action", null)});
		root.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry("First", first), new StubMenuEntry("Second", second)});
		int[] pixels = new int[120 * 100];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		SubmenuTree tree = SubmenuTree.capture(root);
		assertEquals(NativeSubmenuProbe.State.NO_CHILD,
			probe.samplePixels(pixels, 120, 100, tree,
				() -> paint(pixels, 120, 100, root)).state);

		first.setBounds(50, 15, 35, 34);
		tree = SubmenuTree.capture(root);
		NativeSubmenuProbe.Result activeFirst = probe.samplePixels(pixels, 120, 100,
			tree, () -> paint(pixels, 120, 100, root, first));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, activeFirst.state);
		assertTrue(Arrays.equals(new int[]{0}, activeFirst.activeChain));

		first.setBounds(0, 0, 0, 0);
		tree = SubmenuTree.capture(root);
		assertEquals(NativeSubmenuProbe.State.NO_CHILD,
			probe.samplePixels(pixels, 120, 100, tree,
				() -> paint(pixels, 120, 100, root)).state);

		second.setBounds(50, 30, 35, 34);
		tree = SubmenuTree.capture(root);
		NativeSubmenuProbe.Result activeSecond = probe.samplePixels(pixels, 120, 100,
			tree, () -> paint(pixels, 120, 100, root, second));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, activeSecond.state);
		assertTrue(Arrays.equals(new int[]{1}, activeSecond.activeChain));
		assertTrue(Arrays.equals(new int[120 * 100], pixels));

		StubMenu reopened = new StubMenu(25, 25, 40, 49);
		StubMenu unopenedChild = new StubMenu(0, 0, 0, 0);
		unopenedChild.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		reopened.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", unopenedChild)});
		assertEquals(NativeSubmenuProbe.State.NO_CHILD,
			probe.samplePixels(pixels, 120, 100, SubmenuTree.capture(reopened),
				() -> paint(pixels, 120, 100, reopened)).state);
	}

	@Test
	public void childGainingBoundsDuringDrawRequiresFreshProbe()
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu child = new StubMenu(0, 0, 0, 0);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", child)});
		int[] pixels = new int[100 * 80];
		SubmenuTree before = SubmenuTree.capture(root);
		NativeSubmenuProbe.Result changed = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			before, () ->
			{
				child.setBounds(50, 15, 30, 34);
				paint(pixels, 100, 80, root, child);
			});
		assertEquals(NativeSubmenuProbe.State.GEOMETRY_CHANGED, changed.state);
		assertTrue(Arrays.equals(new int[8000], pixels));
		NativeSubmenuProbe.Result retried = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			SubmenuTree.capture(root), () -> paint(pixels, 100, 80, root, child));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, retried.state);
		assertTrue(Arrays.equals(new int[]{0}, retried.activeChain));
	}

	@Test
	public void offCanvasStaleChildDoesNotForceVanillaRoot()
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu staleChild = new StubMenu(150, 20, 30, 34);
		staleChild.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", staleChild)});
		int[] pixels = new int[100 * 80];
		NativeSubmenuProbe.Result rootOnly = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			SubmenuTree.capture(root), () -> paint(pixels, 100, 80, root));
		assertEquals(NativeSubmenuProbe.State.NO_CHILD, rootOnly.state);
		assertTrue(Arrays.equals(new int[8000], pixels));
	}

	@Test
	public void threePlayerTransitionsIgnoreDormantOverlappingSeventhCandidate()
	{
		StubMenu root = new StubMenu(20, 10, 50, 109);
		StubMenu[] children = new StubMenu[7];
		MenuEntry[] entries = new MenuEntry[7];
		for (int i = 0; i < children.length; i++)
		{
			children[i] = new StubMenu(0, 0, 0, 0);
			children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			entries[i] = new StubMenuEntry("Player " + i, children[i]);
		}
		children[0].setBounds(70, 15, 50, 34);
		children[1].setBounds(70, 45, 50, 34);
		children[2].setBounds(70, 75, 50, 34);
		// A stale seventh sibling has no exclusive pixels inside child 1.
		children[6].setBounds(75, 50, 35, 20);
		root.setMenuEntries(entries);
		int[] pixels = new int[160 * 130];
		int[] sequence = {-1, 0, 1, 2, 0, 2, 1, -1};
		for (int active : sequence)
		{
			SubmenuTree tree = SubmenuTree.capture(root);
			NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(
				pixels, 160, 130, tree, () ->
				{
					paint(pixels, 160, 130, root);
					if (active >= 0)
					{
						paint(pixels, 160, 130, children[active]);
					}
				});
			assertEquals("active=" + active + " reason=" + result.reason,
				active < 0 ? NativeSubmenuProbe.State.NO_CHILD : NativeSubmenuProbe.State.ACTIVE,
				result.state);
			assertTrue(Arrays.equals(active < 0 ? new int[0] : new int[]{active}, result.activeChain));
			assertEquals(0, result.witnesses[6]);
			assertTrue(result.sharedWitnesses[6] > 0);
			assertEquals(active == 1 ? result.sharedWitnesses[6] : 0, result.sharedChanged[6]);
			assertTrue(result.restored);
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void identifiesAllFiveRetainedPlayerRectanglesByNativeFootprint()
	{
		int[][] nativeBounds = {
			{480, 326, 222, 67}, {489, 311, 213, 67}, {518, 296, 184, 67},
			{517, 281, 185, 67}, {487, 266, 215, 67}
		};
		StubMenu root = new StubMenu(702, 250, 160, 150);
		StubMenu[] children = new StubMenu[5];
		MenuEntry[] entries = new MenuEntry[5];
		for (int i = 0; i < children.length; i++)
		{
			int[] b = nativeBounds[i];
			children[i] = new StubMenu(b[0], b[1], b[2], b[3]);
			children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			entries[i] = new StubMenuEntry("Player " + i, children[i]);
		}
		root.setMenuEntries(entries);
		int[] pixels = new int[900 * 500];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		int[] sequence = {-1, 0, 1, 2, 3, 4, 3, 1, 4, 2, 0, -1};
		int oldFallbacks = 0;
		int[] nativeDraws = {0};
		for (int active : sequence)
		{
			NativeSubmenuProbe.Result result = probe.samplePixels(pixels, 900, 500,
				SubmenuTree.capture(root), () ->
				{
					nativeDraws[0]++;
					paint(pixels, 900, 500, root);
					if (active >= 0)
					{
						paint(pixels, 900, 500, children[active]);
					}
				});
			assertEquals("child=" + active + " reason=" + result.reason,
				active < 0 ? NativeSubmenuProbe.State.NO_CHILD : NativeSubmenuProbe.State.ACTIVE,
				result.state);
			assertTrue("wrong child=" + active,
				Arrays.equals(active < 0 ? new int[0] : new int[]{active}, result.activeChain));
			if (active >= 1 && active <= 3)
			{
				assertEquals(0, result.witnesses[active]);
				assertTrue(result.sharedChanged[active] > 0);
				assertEquals(1, result.footprintMatches);
				assertEquals(1, result.drawPasses);
				oldFallbacks++;
			}
			assertTrue(result.restored);
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
		assertEquals("previous overlap guard rejected these six frames", 6, oldFallbacks);
		assertEquals("one native draw per sample", sequence.length, nativeDraws[0]);
		NativeSubmenuProbe.Result combined = probe.samplePixels(pixels, 900, 500,
			SubmenuTree.capture(root), () -> paint(pixels, 900, 500, root, children[1], children[3]));
		assertEquals("two visible siblings must not become one themed child",
			NativeSubmenuProbe.State.AMBIGUOUS, combined.state);
		assertEquals(0, combined.footprintMatches);
		try
		{
			probe.samplePixels(pixels, 900, 500, SubmenuTree.capture(root), () ->
			{
				paint(pixels, 900, 500, root, children[2]);
				throw new IllegalStateException("synthetic draw failure");
			});
			throw new AssertionError("expected native draw exception");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
		NativeSubmenuProbe.Result afterFailure = probe.samplePixels(pixels, 900, 500,
			SubmenuTree.capture(root), () -> paint(pixels, 900, 500, root, children[2]));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, afterFailure.state);
		assertTrue(Arrays.equals(new int[]{2}, afterFailure.activeChain));
	}

	@Test
	public void footprintHandlesClippedAndRootOverlappingFiveChildMenus()
	{
		for (boolean opensLeft : new boolean[]{true, false})
		{
			StubMenu root = opensLeft ? new StubMenu(680, 250, 160, 150)
				: new StubMenu(10, 250, 160, 150);
			int[][] bounds = {
				{480, 326, 222, 67}, {489, 311, 213, 67}, {518, 296, 184, 67},
				{517, 281, 185, 67}, {487, 266, 215, 67}
			};
			StubMenu[] children = new StubMenu[5];
			MenuEntry[] entries = new MenuEntry[5];
			for (int i = 0; i < children.length; i++)
			{
				int x = opensLeft ? bounds[i][0] : 170;
				children[i] = new StubMenu(x, bounds[i][1], bounds[i][2], bounds[i][3]);
				children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
				entries[i] = new StubMenuEntry("Player " + i, children[i]);
			}
			root.setMenuEntries(entries);
			int width = opensLeft ? 900 : 360;
			int[] pixels = new int[width * 500];
			NativeSubmenuProbe probe = new NativeSubmenuProbe();
			for (int active = 0; active < children.length; active++)
			{
				StubMenu child = children[active];
				NativeSubmenuProbe.Result result = probe.samplePixels(pixels, width, 500,
					SubmenuTree.capture(root), () -> paint(pixels, width, 500, root, child));
				assertEquals("opensLeft=" + opensLeft + " child=" + active + " reason=" + result.reason,
					NativeSubmenuProbe.State.ACTIVE, result.state);
				assertTrue(Arrays.equals(new int[]{active}, result.activeChain));
			}
		}
	}

	@Test
	public void footprintResolvesNestedChildWhenParentHasNoExclusivePixels()
	{
		StubMenu root = new StubMenu(702, 250, 160, 150);
		StubMenu parent = new StubMenu(518, 296, 184, 67);
		StubMenu nested = new StubMenu(400, 305, 118, 50);
		StubMenu upper = new StubMenu(487, 266, 215, 67);
		StubMenu lower = new StubMenu(480, 326, 222, 67);
		nested.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		parent.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Nested", nested)});
		upper.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Upper", null)});
		lower.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Lower", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Parent", parent),
			new StubMenuEntry("Upper", upper), new StubMenuEntry("Lower", lower)});
		int[] pixels = new int[900 * 500];
		NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(pixels, 900, 500,
			SubmenuTree.capture(root), () -> paint(pixels, 900, 500, root, parent, nested));
		assertEquals(result.reason, NativeSubmenuProbe.State.ACTIVE, result.state);
		assertTrue(Arrays.equals(new int[]{0, 1}, result.activeChain));
		assertEquals(0, result.witnesses[0]);
		assertEquals(1, result.footprintMatches);
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	@Test
	public void indistinguishableActiveSiblingsStillFallBack()
	{
		StubMenu root = new StubMenu(10, 10, 40, 64);
		StubMenu first = new StubMenu(50, 20, 35, 34);
		StubMenu second = new StubMenu(50, 20, 35, 34);
		first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("A", null)});
		second.setMenuEntries(new MenuEntry[]{new StubMenuEntry("B", null)});
		root.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry("First", first), new StubMenuEntry("Second", second)});
		int[] pixels = new int[100 * 80];
		SubmenuTree tree = SubmenuTree.capture(root);
		NativeSubmenuProbe.Result dormant = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			tree, () -> paint(pixels, 100, 80, root));
		assertEquals(NativeSubmenuProbe.State.NO_CHILD, dormant.state);
		NativeSubmenuProbe.Result active = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			tree, () -> paint(pixels, 100, 80, root, first));
		assertEquals(NativeSubmenuProbe.State.AMBIGUOUS, active.state);
		assertTrue(active.reason.startsWith("ambiguous geometry index="));
		assertTrue(active.sharedChanged[0] > 0);
		assertEquals(2, active.footprintMatches);
		assertTrue(active.restored);
	}

	@Test
	public void loggedGeScrollHasTwoIdenticalRetainedChildBounds()
	{
		LoggedGeMenus menus = loggedGeMenus(98, 3, 372, 75, 83,
			new Rectangle(620, 210, 229, 82), new Rectangle(620, 210, 229, 82));
		SubmenuTree tree = SubmenuTree.capture(menus.root, menus.entries, 8);
		assertEquals(87, tree.candidateBounds.size());
		assertEquals(new Rectangle(620, 210, 229, 82), tree.candidateBounds.get(72));
		assertEquals(tree.candidateBounds.get(72), tree.candidateBounds.get(80));
		assertEquals(-1, (int) tree.candidateParents.get(72));
		assertEquals(-1, (int) tree.candidateParents.get(80));
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
			tree.nodes.get(0).bounds, 98, 990, 592, true, 8);
		assertTrue(viewport.containsRow(22));
		assertTrue(viewport.containsRow(14));
		assertEquals(229, viewport.rowTop(22));
		assertEquals(109, viewport.rowTop(14));
		int[] pixels = new int[990 * 592];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		NativeSubmenuProbe.Result first = probe.samplePixels(pixels, 990, 592, tree,
			() -> paint(pixels, 990, 592, menus.root, menus.first));
		NativeSubmenuProbe.Result second = probe.samplePixels(pixels, 990, 592, tree,
			() -> paint(pixels, 990, 592, menus.root, menus.second));
		for (NativeSubmenuProbe.Result result : new NativeSubmenuProbe.Result[]{first, second})
		{
			assertEquals(NativeSubmenuProbe.State.AMBIGUOUS, result.state);
			assertEquals(2, result.footprintMatches);
			assertTrue(result.restored);
		}
		assertEquals(first.footprintBounds, second.footprintBounds);
		assertTrue(Arrays.equals(first.changed, second.changed));
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
		// Above-scroll parents are omitted by capture; this is the existing safe pruning.
		SubmenuTree afterScroll = SubmenuTree.capture(menus.root, menus.entries, 15);
		assertEquals(80, afterScroll.candidateBounds.size());
		assertEquals(new Rectangle(620, 210, 229, 82), afterScroll.candidateBounds.get(72));
	}

	@Test
	public void loggedGeScrollNearWidthsRemainIndistinguishable()
	{
		LoggedGeMenus menus = loggedGeMenus(96, 2, 374, 36, 59,
			new Rectangle(622, 135, 247, 82), new Rectangle(622, 135, 244, 82));
		SubmenuTree tree = SubmenuTree.capture(menus.root, menus.entries, 25);
		assertEquals(69, tree.candidateBounds.size());
		assertEquals(new Rectangle(622, 135, 247, 82), tree.candidateBounds.get(34));
		assertEquals(new Rectangle(622, 135, 244, 82), tree.candidateBounds.get(57));
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
			tree.nodes.get(0).bounds, 96, 990, 592, true, 25);
		assertTrue(viewport.containsRow(59));
		assertTrue(viewport.containsRow(36));
		assertEquals(529, viewport.rowTop(59));
		assertEquals(184, viewport.rowTop(36));
		int[] pixels = new int[990 * 592];
		NativeSubmenuProbe.Result result = new NativeSubmenuProbe().samplePixels(
			pixels, 990, 592, tree, () -> paint(pixels, 990, 592, menus.root, menus.second));
		assertEquals(result.reason, NativeSubmenuProbe.State.AMBIGUOUS, result.state);
		assertEquals(2, result.footprintMatches);
		assertTrue(result.restored);
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	@Test
	public void visibleChildDoesNotDisproveOffscreenParentsRetainedRectangle()
	{
		LoggedGeMenus menus = loggedGeMenus(96, 2, 374, 36, 59,
			new Rectangle(622, 135, 244, 82), new Rectangle(622, 135, 244, 82));
		SubmenuTree tree = SubmenuTree.capture(menus.root, menus.entries, 0);
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
			tree.nodes.get(0).bounds, 96, 990, 592, true, 0);
		assertFalse(viewport.containsRow(59));
		assertTrue(viewport.containsRow(36));
		int[] pixels = new int[990 * 592];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		for (StubMenu drawn : new StubMenu[]{menus.first, menus.second})
		{
			NativeSubmenuProbe.Result result = probe.samplePixels(pixels, 990, 592,
				tree, () -> paint(pixels, 990, 592, menus.root, drawn));
			assertEquals(NativeSubmenuProbe.State.AMBIGUOUS, result.state);
			assertEquals(2, result.footprintMatches);
			assertTrue(result.restored);
		}
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	private static LoggedGeMenus loggedGeMenus(int rootRows, int plainPrefix, int rootX,
		int firstEntry, int secondEntry, Rectangle firstBounds, Rectangle secondBounds)
	{
		StubMenu root = new StubMenu(rootX, 0, 248,
			MenuRenderer.HEADER_HEIGHT + rootRows * MenuRenderer.ROW_HEIGHT + 3);
		MenuEntry[] entries = new MenuEntry[rootRows];
		StubMenu first = null;
		StubMenu second = null;
		for (int i = 0; i < rootRows; i++)
		{
			StubMenu child = null;
			if (i >= plainPrefix)
			{
				Rectangle bounds = i == firstEntry ? firstBounds
					: i == secondEntry ? secondBounds : new Rectangle();
				child = new StubMenu(bounds.x, bounds.y, bounds.width, bounds.height);
				child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
				if (i == firstEntry) first = child;
				if (i == secondEntry) second = child;
			}
			entries[i] = new StubMenuEntry("Player " + i, child);
		}
		root.setMenuEntries(entries);
		return new LoggedGeMenus(root, entries, first, second);
	}

	private static final class LoggedGeMenus
	{
		private final StubMenu root;
		private final MenuEntry[] entries;
		private final StubMenu first;
		private final StubMenu second;

		private LoggedGeMenus(StubMenu root, MenuEntry[] entries, StubMenu first, StubMenu second)
		{
			this.root = root;
			this.entries = entries;
			this.first = first;
			this.second = second;
		}
	}

	@Test
	public void detectsPartiallyVisibleChildrenAtBothCanvasEdges()
	{
		StubMenu root = new StubMenu(40, 10, 30, 64);
		StubMenu left = new StubMenu(-25, 15, 30, 34);
		StubMenu right = new StubMenu(95, 45, 30, 34);
		left.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Left", null)});
		right.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Right", null)});
		root.setMenuEntries(new MenuEntry[]{
			new StubMenuEntry("Left", left), new StubMenuEntry("Right", right)});
		int[] pixels = new int[100 * 80];
		SubmenuTree tree = SubmenuTree.capture(root);
		NativeSubmenuProbe.Result rootOnly = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			tree, () -> paint(pixels, 100, 80, root));
		assertEquals(NativeSubmenuProbe.State.NO_CHILD, rootOnly.state);
		for (int index = 0; index < 2; index++)
		{
			StubMenu child = index == 0 ? left : right;
			NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(pixels, 100, 80,
				tree, () -> paint(pixels, 100, 80, root, child));
			assertEquals(NativeSubmenuProbe.State.ACTIVE, result.state);
			assertTrue(Arrays.equals(new int[]{index}, result.activeChain));
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void nonzeroChildMovingDuringDrawRequiresFreshGeometry()
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu child = new StubMenu(50, 15, 30, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", child)});
		int[] pixels = new int[100 * 80];
		NativeSubmenuProbe.Result changed = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			SubmenuTree.capture(root), () ->
			{
				child.setBounds(70, 15, 30, 34);
				paint(pixels, 100, 80, root, child);
			});
		assertEquals(NativeSubmenuProbe.State.GEOMETRY_CHANGED, changed.state);
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
		NativeSubmenuProbe.Result retried = NativeSubmenuProbe.probePixels(pixels, 100, 80,
			SubmenuTree.capture(root), () -> paint(pixels, 100, 80, root, child));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, retried.state);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidRootBounds()
	{
		StubMenu root = new StubMenu(10, 10, 0, 49);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		SubmenuTree.capture(root);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNegativeChildBounds()
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu child = new StubMenu(50, 15, -1, 34);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", child)});
		SubmenuTree.capture(root);
	}

	@Test
	public void preparedFrameCannotSurviveRootClosureOrReopen()
	{
		StubMenu firstOpen = new StubMenu(10, 10, 40, 49);
		StubMenu secondOpen = new StubMenu(20, 20, 40, 49);
		assertTrue(MenuOverlay.preparedFrameMatches(true, firstOpen, firstOpen));
		assertFalse(MenuOverlay.preparedFrameMatches(false, firstOpen, firstOpen));
		assertFalse(MenuOverlay.preparedFrameMatches(true, secondOpen, firstOpen));
	}

	@Test
	public void preparedFrameRejectsChangedEntriesOnSameRoot()
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		StubMenuEntry entry = new StubMenuEntry("Original", null);
		root.setMenuEntries(new MenuEntry[]{entry});
		PreparedMenuState state = PreparedMenuState.capture(SubmenuTree.capture(root), new int[0]);
		assertTrue(state.matches(root));
		entry.setOption("Changed");
		assertFalse(state.matches(root));
		entry.setOption("Original");
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Replacement", null)});
		assertFalse(state.matches(root));
	}

	@Test
	public void preparedFrameRejectsChangedActiveChildWithSameRootAndScroll()
	{
		StubMenu root = new StubMenu(10, 10, 80, 64);
		StubMenu first = new StubMenu(90, 20, 80, 49);
		StubMenu second = new StubMenu(90, 35, 80, 49);
		first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("First action", null)});
		second.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Second action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("First", first),
			new StubMenuEntry("Second", second)});
		PreparedMenuState state = PreparedMenuState.capture(SubmenuTree.capture(root), new int[]{0});
		assertTrue(state.matches(root));
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("First", second),
			new StubMenuEntry("Second", first)});
		assertFalse(state.matches(root));
	}

	@Test
	public void preparedFrameRejectsDetachedGeometryAndReplacedChild()
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		StubMenu child = new StubMenu(90, 20, 80, 49);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", child)});
		PreparedMenuState state = PreparedMenuState.capture(SubmenuTree.capture(root), new int[]{0});
		assertTrue(state.matches(root));
		child.setBounds(0, 0, 0, 0);
		assertEquals("geometry node=1", state.mismatchReason(root));
		child.setBounds(90, 20, 80, 49);
		assertTrue(state.matches(root));
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Open", new StubMenu(90, 20, 80, 49))});
		assertEquals("entry submenu depth=0 index=0", state.mismatchReason(root));
	}

	@Test
	public void preparedStateReportsEachStructuralMismatch()
	{
		StubMenu root = new StubMenu(10, 10, 80, 64);
		StubMenu child = new StubMenu(90, 20, 80, 49);
		StubMenuEntry childAction = new StubMenuEntry("Child action", null);
		child.setMenuEntries(new MenuEntry[]{childAction});
		StubMenuEntry parent = new StubMenuEntry("Open", child);
		MenuEntry[] rootEntries = {parent, new StubMenuEntry("Other", null)};
		root.setMenuEntries(rootEntries);
		PreparedMenuState state = PreparedMenuState.capture(SubmenuTree.capture(root), new int[]{0});
		assertEquals(null, state.mismatchReason(root));
		assertEquals("root identity", state.mismatchReason(new StubMenu(10, 10, 80, 64)));
		parent.setOption("Changed");
		assertEquals("entry option depth=0 index=0", state.mismatchReason(root));
		parent.setOption("Open");
		childAction.setOption("Changed");
		assertEquals("entry option depth=1 index=0", state.mismatchReason(root));
		childAction.setOption("Child action");
		child.setBounds(90, 21, 80, 49);
		assertEquals("geometry node=1", state.mismatchReason(root));
		child.setBounds(90, 20, 80, 49);
		child.setMenuEntries(new MenuEntry[]{childAction, new StubMenuEntry("Extra", null)});
		assertEquals("entry count depth=1", state.mismatchReason(root));
		child.setMenuEntries(new MenuEntry[]{null});
		assertEquals("entry presence depth=1 index=0", state.mismatchReason(root));
		child.setMenuEntries(new MenuEntry[]{childAction});
		assertEquals(null, state.mismatchReason(root));
	}

	@Test
	public void preparedFrameRejectsClosedAndReplacedRoot()
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		PreparedMenuState state = PreparedMenuState.capture(SubmenuTree.capture(root), new int[0]);
		assertFalse(state.matches(null));
		StubMenu reopened = new StubMenu(10, 10, 80, 49);
		reopened.setMenuEntries(root.getMenuEntries());
		assertFalse(state.matches(reopened));
	}

	@Test
	public void changedStateIsDroppedBeforeFinalDraw() throws Exception
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		StubMenuEntry entry = new StubMenuEntry("Action", null);
		root.setMenuEntries(new MenuEntry[]{entry});
		MenuOverlay overlay = preparedOverlay(root);
		entry.setOption("Changed");
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals("consumed frame cannot restore native pixels", 0, image.getRGB(10, 10));
		assertEquals(1L, privateField(overlay, "submenuChangedDrops"));
		assertFalse((boolean) privateField(overlay, "prepared"));
	}

	@Test
	public void finalBlitFailureDisablesFutureConsumption() throws Exception
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		MenuOverlay overlay = preparedOverlay(root);
		overlay.render(null);
		assertTrue((boolean) privateField(overlay, "submenuBlitFailed"));
		assertFalse((boolean) privateField(overlay, "prepared"));
		assertFalse("later menu events must remain native",
			overlay.prepareSubmenuFrame(root, root.getMenuEntries(), new Point(20, 30)));
	}

	@Test
	public void consumedEventCannotBeOverpaintedByTheRootOverlay() throws Exception
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		MenuOverlay overlay = preparedOverlay(root);
		RightClickStylerPlugin plugin = new RightClickStylerPlugin();
		setPrivateField(plugin, "menuOverlay", overlay);
		BeforeMenuRender event = new BeforeMenuRender();
		event.consume();
		plugin.onBeforeMenuRender(event);
		BufferedImage image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			overlay.render(graphics);
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals("another renderer owns this frame", 0, image.getRGB(20, 30));
		assertFalse((boolean) privateField(overlay, "prepared"));
	}

	@Test
	public void transparentReplacementPreservesSceneAtZeroThemeOpacity()
	{
		BufferedImage replacement = new BufferedImage(100, 70, BufferedImage.TYPE_INT_ARGB);
		Graphics2D prepared = replacement.createGraphics();
		try
		{
			prepared.setColor(Color.BLACK);
			prepared.fillRect(0, 0, 100, 70);
			prepared.setComposite(AlphaComposite.Clear);
			prepared.fillRect(0, 0, 100, 70);
			prepared.setComposite(AlphaComposite.SrcOver);
			Theme theme = BuiltInThemes.find("classic").withOpacity(0)
				.withFont(FontChoice.SANS).withShowHeader(false)
				.withShowBorder(false).withShowShadow(false).withTextShadow(false);
			new MenuRenderer().render(prepared, null, theme, new RightClickStylerConfig() { },
				Collections.singletonList(new MenuRenderer.Row("Action", "", MenuRenderer.TargetKind.OTHER)),
				new Rectangle(10, 10, 80, 49), -1, 0);
		}
		finally
		{
			prepared.dispose();
		}
		BufferedImage frame = new BufferedImage(100, 70, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = frame.createGraphics();
		try
		{
			graphics.setColor(Color.MAGENTA);
			graphics.fillRect(0, 0, 100, 70);
			graphics.drawImage(replacement, 0, 0, null);
		}
		finally
		{
			graphics.dispose();
		}
		assertEquals("gap outside menu stays transparent", 0, replacement.getRGB(95, 65));
		assertEquals("underlying scene outside menu stays visible", Color.MAGENTA.getRGB(), frame.getRGB(95, 65));
		assertEquals("zero-opacity fill reveals the scene", Color.MAGENTA.getRGB(), frame.getRGB(80, 50));
		boolean textVisible = false;
		for (int y = 29; y < 44; y++)
		{
			for (int x = 13; x < 70; x++)
			{
				textVisible |= frame.getRGB(x, y) != Color.MAGENTA.getRGB();
			}
		}
		assertTrue("text remains visible", textVisible);
	}

	@Test
	public void menuOpenedInvalidatesPendingFrame() throws Exception
	{
		StubMenu root = new StubMenu(10, 10, 80, 49);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
		MenuOverlay overlay = preparedOverlay(root);
		RightClickStylerPlugin plugin = new RightClickStylerPlugin();
		setPrivateField(plugin, "menuOverlay", overlay);
		plugin.onMenuOpened(null);
		assertFalse((boolean) privateField(overlay, "prepared"));
	}

	private static MenuOverlay preparedOverlay(StubMenu root) throws Exception
	{
		Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
			{
			switch (method.getName())
				{
				case "isMenuOpen": return true;
				case "getMenu": return root;
				case "isMenuScrollable": return false;
				case "getMenuScroll": return 0;
				case "getCanvasWidth": return 100;
				case "getCanvasHeight": return 100;
				case "getMouseCanvasPosition":
					throw new AssertionError("the prepared lifecycle must not re-read the mouse");
				default: throw new UnsupportedOperationException(method.getName());
				}
			});
		Constructor<MenuOverlay> constructor = MenuOverlay.class.getDeclaredConstructor(
			Client.class, RightClickStylerConfig.class, ThemeManager.class);
		constructor.setAccessible(true);
		MenuOverlay overlay = constructor.newInstance(client, null, null);
		setPrivateField(overlay, "prepared", true);
		setPrivateField(overlay, "preparedRoot", root);
		setPrivateField(overlay, "preparedState", PreparedMenuState.capture(
			SubmenuTree.capture(root), new int[0]));
		setPrivateField(overlay, "submenuImage", new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
		return overlay;
	}

	private static Object privateField(Object target, String name) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static void setPrivateField(Object target, String name, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsCyclicMenuGraph()
	{
		StubMenu root = new StubMenu(10, 10, 40, 49);
		StubMenu child = new StubMenu(50, 10, 35, 34);
		root.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Child", child)});
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Back", root)});
		SubmenuTree.capture(root);
	}

	@Test
	public void calculatesIndependentHoverRows()
	{
		Rectangle root = new Rectangle(10, 10, 50, 64);
		Rectangle child = new Rectangle(60, 25, 45, 49);
		assertEquals(0, MenuOverlay.hoverIndex(root, 3, new Point(20, 30)));
		assertEquals(-1, MenuOverlay.hoverIndex(child, 2, new Point(20, 30)));
		assertEquals(1, MenuOverlay.hoverIndex(child, 2, new Point(70, 60)));
		assertEquals(-1, MenuOverlay.hoverIndex(child, 2, new Point(70, 43)));
	}

	@Test
	public void scrollViewportMapsTopMiddleBottomRowsAndHover()
	{
		Rectangle root = new Rectangle(10, 0, 180, 619);
		int[] offsets = {0, 10, 22};
		for (int scroll : offsets)
		{
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				root, 40, 200, 300, true, scroll);
			assertTrue(viewport != null);
			assertEquals(scroll, viewport.firstRow);
			assertEquals(Math.min(40, scroll + 19), viewport.endRow);
			assertEquals(scroll, viewport.hoverIndex(new Point(20, 20)));
			assertEquals(scroll + 1, viewport.hoverIndex(new Point(20, 35)));
			assertEquals(-1, viewport.hoverIndex(new Point(20, 18)));
			assertEquals(-1, viewport.hoverIndex(new Point(20, 300)));
		}
		assertEquals(null, ScrollableMenuViewport.capture(root, 40, 200, 300, true, 23));
		assertEquals(null, ScrollableMenuViewport.capture(root, 40, 200, 300, true, -1));
		assertEquals(null, ScrollableMenuViewport.capture(
			new Rectangle(10, 0, 180, 280), 40, 200, 300, true, 1));
		assertTrue(ScrollableMenuViewport.capture(
			new Rectangle(10, 10, 180, 80), 4, 200, 300, false, 0) != null);
	}

	@Test
	public void scrolledRendererMatchesVisibleRowSubsetAndKeepsHeader()
	{
		List<MenuRenderer.Row> all = new ArrayList<>();
		for (int i = 0; i < 40; i++)
		{
			all.add(new MenuRenderer.Row("Player " + i, "", MenuRenderer.TargetKind.PLAYER));
		}
		Theme theme = BuiltInThemes.find("classic").withShowShadow(false).withRadius(0);
		RightClickStylerConfig config = new RightClickStylerConfig() { };
		Rectangle bounds = new Rectangle(10, 0, 180, 619);
		for (int scroll : new int[]{0, 10, 22})
		{
			BufferedImage scrolled = new BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB);
			BufferedImage subset = new BufferedImage(200, 300, BufferedImage.TYPE_INT_ARGB);
			Graphics2D a = scrolled.createGraphics();
			Graphics2D b = subset.createGraphics();
			try
			{
				new MenuRenderer().render(a, null, theme, config, all,
					bounds, scroll + 2, 0, "Choose Option", scroll);
				new MenuRenderer().render(b, null, theme, config,
					all.subList(scroll, all.size()), bounds, 2, 0, "Choose Option");
			}
			finally
			{
				a.dispose();
				b.dispose();
			}
			assertTrue("scroll=" + scroll, Arrays.equals(
				scrolled.getRGB(0, 0, 200, 300, null, 0, 200),
				subset.getRGB(0, 0, 200, 300, null, 0, 200)));
		}
	}

	@Test
	public void scrolledChildMustRemainAttachedToVisibleParentRow()
	{
		StubMenu root = new StubMenu(10, 0, 180, 619);
		StubMenu child = new StubMenu(190, 49, 100, 67);
		child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
		MenuEntry[] entries = new MenuEntry[40];
		for (int i = 0; i < entries.length; i++)
		{
			entries[i] = new StubMenuEntry("Player " + i, null);
		}
		entries[27] = new StubMenuEntry("Parent", child); // top-down row 12
		root.setMenuEntries(entries);
		SubmenuTree tree = SubmenuTree.capture(root);
		assertEquals(27, tree.nodes.get(1).parentEntryIndex);
		ScrollableMenuViewport middle = ScrollableMenuViewport.capture(
			new Rectangle(10, 0, 180, 619), 40, 300, 300, true, 10);
		assertEquals(null, MenuOverlay.unsafeScrolledChild(tree, new int[]{0}, middle, 300));
		ScrollableMenuViewport bottom = ScrollableMenuViewport.capture(
			new Rectangle(10, 0, 180, 619), 40, 300, 300, true, 22);
		assertTrue(MenuOverlay.unsafeScrolledChild(tree, new int[]{0}, bottom, 300)
			.contains("outside the root viewport"));
		ScrollableMenuViewport shifted = ScrollableMenuViewport.capture(
			new Rectangle(10, 0, 180, 619), 40, 300, 300, true, 11);
		assertTrue(MenuOverlay.unsafeScrolledChild(tree, new int[]{0}, shifted, 300)
			.contains("detached"));
		child.setBounds(190, 49, 100, 400);
		tree = SubmenuTree.capture(root);
		assertTrue(MenuOverlay.unsafeScrolledChild(tree, new int[]{0}, middle, 300)
			.contains("unavailable submenu scroll offset"));
	}

	@Test
	public void scrollableRootKeepsCorrectFiveChildChainWhileSwitching()
	{
		StubMenu root = new StubMenu(702, 0, 160, 619);
		MenuEntry[] entries = new MenuEntry[40];
		for (int i = 0; i < entries.length; i++)
		{
			entries[i] = new StubMenuEntry("Option " + i, null);
		}
		int[] lefts = {480, 489, 518, 517, 487};
		int[] widths = {222, 213, 184, 185, 215};
		StubMenu[] children = new StubMenu[5];
		for (int i = 0; i < children.length; i++)
		{
			children[i] = new StubMenu(lefts[i], 40 + i * 15, widths[i], 67);
			children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			entries[27 - i] = new StubMenuEntry("Player " + i, children[i]);
		}
		root.setMenuEntries(entries);
		ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
			new Rectangle(702, 0, 160, 619), 40, 900, 300, true, 10);
		int[] pixels = new int[900 * 300];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		for (int active : new int[]{0, 1, 2, 3, 4, 3, 2, 1, 0})
		{
			StubMenu child = children[active];
			SubmenuTree tree = SubmenuTree.capture(root);
			NativeSubmenuProbe.Result result = probe.samplePixels(pixels, 900, 300,
				tree, () -> paint(pixels, 900, 300, root, child));
			assertEquals("child=" + active + " reason=" + result.reason,
				NativeSubmenuProbe.State.ACTIVE, result.state);
			assertTrue(Arrays.equals(new int[]{4 - active}, result.activeChain));
			assertEquals(null, MenuOverlay.unsafeScrolledChild(tree, result.activeChain, viewport, 300));
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void scrollableRootOnlyAndCanvasEdgeChildrenKeepNativeDecision()
	{
		for (boolean opensLeft : new boolean[]{false, true})
		{
			int width = 300;
			int height = 300;
			int rootX = opensLeft ? 120 : 185;
			StubMenu root = new StubMenu(rootX, 0, 110, 619);
			StubMenu child = new StubMenu(opensLeft ? -2 : 295, 242, 122, 67);
			child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			MenuEntry[] entries = new MenuEntry[40];
			for (int i = 0; i < entries.length; i++)
			{
				entries[i] = new StubMenuEntry("Option " + i, null);
			}
			entries[2] = new StubMenuEntry("Player", child); // row 37; bottom scroll=22
			root.setMenuEntries(entries);
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				new Rectangle(rootX, 0, 110, 619), 40, width, height, true, 22);
			assertTrue(viewport != null);
			int[] pixels = new int[width * height];
			NativeSubmenuProbe probe = new NativeSubmenuProbe();
			SubmenuTree tree = SubmenuTree.capture(root);
			NativeSubmenuProbe.Result closed = probe.samplePixels(pixels, width, height,
				tree, () -> paint(pixels, width, height, root));
			assertEquals(NativeSubmenuProbe.State.NO_CHILD, closed.state);
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
			NativeSubmenuProbe.Result active = probe.samplePixels(pixels, width, height,
				tree, () -> paint(pixels, width, height, root, child));
			assertEquals("left=" + opensLeft + " reason=" + active.reason,
				NativeSubmenuProbe.State.ACTIVE, active.state);
			assertTrue(Arrays.equals(new int[]{0}, active.activeChain));
			assertEquals(null, MenuOverlay.unsafeScrolledChild(tree, active.activeChain,
				viewport, height));
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void retainedNativeChildDuringScrollIsThemedOnlyWhileParentVisible()
	{
		int width = 1100;
		int height = 300;
		StubMenu root = new StubMenu(650, 0, 180, 679);
		StubMenu[] children = new StubMenu[44];
		MenuEntry[] entries = new MenuEntry[44];
		for (int i = 0; i < children.length; i++)
		{
			children[i] = new StubMenu(0, 0, 0, 0);
			children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			entries[i] = new StubMenuEntry("Player " + i, children[i]);
		}
		root.setMenuEntries(entries);
		children[25].setBounds(833, 270, 230, 67);
		children[20].setBounds(840, 270, 223, 67);
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		for (int scroll : new int[]{0, 2, 3, 5, 6, 7, 18, 19, 5, 0})
		{
			int activeIndex = scroll == 6 || scroll == 7 ? 20 : 25;
			ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
				new Rectangle(650, 0, 180, 679), 44, width, height, true, scroll);
			assertTrue(viewport != null);
			SubmenuTree tree = SubmenuTree.capture(root, entries, scroll);
			int[] pixels = new int[width * height];
			if (scroll == 19)
			{
				assertFalse(viewport.containsRow(18));
				NativeSubmenuProbe.Result rootOnly = probe.samplePixels(pixels, width, height,
					tree, () -> paint(pixels, width, height, root));
				assertEquals(NativeSubmenuProbe.State.NO_CHILD, rootOnly.state);
				assertTrue(Arrays.equals(new int[pixels.length], pixels));
				continue;
			}
			StubMenu active = children[activeIndex];
			NativeSubmenuProbe.Result result = probe.samplePixels(pixels, width, height,
				tree, () -> paint(pixels, width, height, root, active));
			assertEquals("scroll=" + scroll + " reason=" + result.reason,
				NativeSubmenuProbe.State.ACTIVE, result.state);
			assertTrue(Arrays.equals(new int[]{activeIndex}, result.activeChain));
			assertEquals(activeIndex, tree.nodes.get(activeIndex + 1).parentEntryIndex);
			int row = 43 - activeIndex;
			assertEquals(row, viewport.hoverIndex(new Point(660,
				Math.min(viewport.rowTop(row) + 1, height - 1))));
			MenuOverlay.ScrollChildValidation validation = MenuOverlay.validateScrolledChild(
				tree, result.activeChain, viewport, height, result);
			assertEquals("scroll=" + scroll + " " + validation.reason, null, validation.reason);
			assertEquals(scroll == 3 || scroll == 5 || scroll == 18,
				validation.detached);
			if (validation.detached)
			{
				assertTrue(MenuOverlay.unsafeScrolledChild(tree, result.activeChain,
					viewport, height).contains("detached"));
			}
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void detachedChildRequiresItsOwnNativeEvidenceAndVisibleParent()
	{
		StubMenu root = new StubMenu(650, 0, 180, 679);
		MenuEntry[] entries = new MenuEntry[44];
		for (int i = 0; i < entries.length; i++)
		{
			entries[i] = new StubMenuEntry("Option " + i, null);
		}
		StubMenu child25 = new StubMenu(833, 270, 230, 67);
		child25.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
		StubMenu child26 = new StubMenu(400, 270, 200, 67);
		child26.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
		entries[25] = new StubMenuEntry("Player 25", child25);
		entries[26] = new StubMenuEntry("Player 26", child26);
		root.setMenuEntries(entries);
		SubmenuTree tree = SubmenuTree.capture(root, entries, 3);
		ScrollableMenuViewport scrolled = ScrollableMenuViewport.capture(
			new Rectangle(650, 0, 180, 679), 44, 1100, 300, true, 3);
		int[] pixels = new int[1100 * 300];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		NativeSubmenuProbe.Result rootOnly = probe.samplePixels(pixels, 1100, 300,
			tree, () -> paint(pixels, 1100, 300, root));
		assertEquals(NativeSubmenuProbe.State.NO_CHILD, rootOnly.state);
		assertTrue(MenuOverlay.validateScrolledChild(tree, new int[]{0}, scrolled, 300,
			rootOnly).reason.contains("detached"));
		NativeSubmenuProbe.Result active = probe.samplePixels(pixels, 1100, 300,
			tree, () -> paint(pixels, 1100, 300, root, child25));
		assertEquals(NativeSubmenuProbe.State.ACTIVE, active.state);
		assertTrue(Arrays.equals(new int[]{0}, active.activeChain));
		assertTrue(MenuOverlay.validateScrolledChild(tree, new int[]{1}, scrolled, 300,
			active).reason.contains("detached"));
		ScrollableMenuViewport offscreen = ScrollableMenuViewport.capture(
			new Rectangle(650, 0, 180, 679), 44, 1100, 300, true, 19);
		assertTrue(MenuOverlay.validateScrolledChild(tree, new int[]{0}, offscreen, 300,
			active).reason.contains("outside the root viewport"));
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	@Test
	public void exactProofAppliesTheSameDetachedAndViewportChecks()
	{
		StubMenu root = new StubMenu(650, 0, 180, 679);
		MenuEntry[] entries = new MenuEntry[44];
		for (int i = 0; i < entries.length; i++)
		{
			entries[i] = new StubMenuEntry("Option " + i, null);
		}
		StubMenu child25 = new StubMenu(833, 270, 230, 67);
		child25.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
		StubMenu child26 = new StubMenu(400, 270, 200, 67);
		child26.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
		entries[25] = new StubMenuEntry("Player 25", child25);
		entries[26] = new StubMenuEntry("Player 26", child26);
		root.setMenuEntries(entries);
		SubmenuTree tree = SubmenuTree.capture(root, entries, 3);
		ScrollableMenuViewport scrolled = ScrollableMenuViewport.capture(
			new Rectangle(650, 0, 180, 679), 44, 1100, 300, true, 3);
		int[] pixels = new int[1100 * 300];
		NativeSubmenuProbe probe = new NativeSubmenuProbe();
		NativeSubmenuProbe.Result rootOnly = probe.samplePixels(pixels, 1100, 300,
			tree, () -> paint(pixels, 1100, 300, root));
		assertEquals(ExactCoverageResolver.State.RESOLVED, rootOnly.exact.state);
		assertTrue(MenuOverlay.validateScrolledChild(tree, new int[]{0}, scrolled, 300,
			rootOnly).reason.contains("detached"));
		NativeSubmenuProbe.Result active = probe.samplePixels(pixels, 1100, 300,
			tree, () -> paint(pixels, 1100, 300, root, child25));
		assertEquals(ExactCoverageResolver.State.RESOLVED, active.exact.state);
		assertTrue(Arrays.equals(new int[]{0}, active.exact.chain));
		MenuOverlay.ScrollChildValidation proven = MenuOverlay.validateScrolledChild(tree, new int[]{0},
			scrolled, 300, active);
		assertEquals(null, proven.reason);
		assertTrue("drawn at its retained position, proven by the exact reading", proven.detached);
		assertTrue("a child off the resolved chain is never proven",
			MenuOverlay.validateScrolledChild(tree, new int[]{1}, scrolled, 300, active)
				.reason.contains("detached"));
		ScrollableMenuViewport offscreen = ScrollableMenuViewport.capture(
			new Rectangle(650, 0, 180, 679), 44, 1100, 300, true, 19);
		assertTrue("a scrolled-out parent stays unsupported",
			MenuOverlay.validateScrolledChild(tree, new int[]{0}, offscreen, 300, active)
				.reason.contains("outside the root viewport"));
		active.exact = new ExactCoverageResolver.Outcome(ExactCoverageResolver.State.AMBIGUOUS,
			new int[0], 2, new int[]{0}, active.exact.changedPixels);
		assertTrue("an unresolved reading proves nothing",
			MenuOverlay.validateScrolledChild(tree, new int[]{0}, scrolled, 300, active)
				.reason.contains("exact=AMBIGUOUS"));
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	@Test
	public void largeScrollableMenusKeepAbsoluteParentAndHoverIndexes()
	{
		for (int count : new int[]{20, 40, 60, 120})
		{
			int menuHeight = MenuRenderer.HEADER_HEIGHT + count * MenuRenderer.ROW_HEIGHT;
			int maxScroll = (menuHeight - 300 + 14) / 15;
			for (int scroll : new int[]{0, maxScroll / 2, maxScroll})
			{
				StubMenu root = new StubMenu(500, 0, 160, menuHeight);
				MenuEntry[] entries = new MenuEntry[count];
				StubMenu[] children = new StubMenu[count];
				for (int i = 0; i < count; i++)
				{
					children[i] = new StubMenu(0, 0, 0, 0);
					children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
					entries[i] = new StubMenuEntry("Player " + i, children[i]);
				}
				root.setMenuEntries(entries);
				ScrollableMenuViewport viewport = ScrollableMenuViewport.capture(
					new Rectangle(500, 0, 160, menuHeight), count, 900, 300, true, scroll);
				assertTrue(viewport != null);
				for (int row : new int[]{viewport.firstRow, viewport.endRow - 1})
				{
					int entryIndex = count - 1 - row;
					int rowTop = viewport.rowTop(row);
					int childY = Math.min(rowTop, 233);
					for (boolean opensLeft : new boolean[]{false, true})
					{
						children[entryIndex].setBounds(opensLeft ? 300 : 660,
							childY, 180, 67);
						SubmenuTree tree = SubmenuTree.capture(root, entries, scroll);
						assertEquals(count - scroll, tree.candidateBounds.size());
						assertEquals(row, viewport.hoverIndex(new Point(510,
							Math.min(rowTop + 1, 299))));
						int[] pixels = new int[900 * 300];
						StubMenu active = children[entryIndex];
						NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(
							pixels, 900, 300, tree,
							() -> paint(pixels, 900, 300, root, active));
						assertEquals("count=" + count + " scroll=" + scroll + " row=" + row
							+ " reason=" + result.reason,
							NativeSubmenuProbe.State.ACTIVE, result.state);
						assertTrue(Arrays.equals(new int[]{entryIndex}, result.activeChain));
						assertEquals(entryIndex, tree.nodes.get(entryIndex + 1).parentEntryIndex);
						assertEquals(null, MenuOverlay.unsafeScrolledChild(tree,
							result.activeChain, viewport, 300));
						assertTrue(Arrays.equals(new int[pixels.length], pixels));
						children[entryIndex].setBounds(0, 0, 0, 0);
					}
				}
			}
		}
	}

	@Test
	public void footprintMatcherDistinguishesCandidateIndexesBeyondThirtyOne()
	{
		StubMenu root = new StubMenu(702, 0, 160, 619);
		MenuEntry[] entries = new MenuEntry[40];
		StubMenu[] children = new StubMenu[40];
		for (int i = 0; i < 40; i++)
		{
			children[i] = new StubMenu(0, 0, 0, 0);
			children[i].setMenuEntries(new MenuEntry[]{new StubMenuEntry("Follow", null)});
			entries[i] = new StubMenuEntry("Player " + i, children[i]);
		}
		children[34].setBounds(480, 100, 220, 67);
		children[35].setBounds(489, 85, 211, 67);
		root.setMenuEntries(entries);
		for (int activeIndex : new int[]{34, 35})
		{
			int[] pixels = new int[900 * 300];
			NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(
				pixels, 900, 300, SubmenuTree.capture(root),
				() -> paint(pixels, 900, 300, root, children[activeIndex]));
			assertEquals("index=" + activeIndex + " reason=" + result.reason,
				NativeSubmenuProbe.State.ACTIVE, result.state);
			assertTrue(Arrays.equals(new int[]{activeIndex}, result.activeChain));
			assertTrue(Arrays.equals(new int[pixels.length], pixels));
		}
	}

	@Test
	public void distinctTreeLimitsRemainEnforced()
	{
		StubMenu root = new StubMenu(10, 0, 160, 619);
		StubMenu first = root;
		for (int i = 0; i <= SubmenuTree.MAX_DEPTH; i++)
		{
			StubMenu next = new StubMenu(200 + i, 30, 100, 34);
			first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Next", next)});
			first = next;
		}
		first.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		assertTreeError(root, "menu depth limit exceeded depth=9");

		root = new StubMenu(10, 0, 160, 8000);
		MenuEntry[] entries = new MenuEntry[512];
		for (int i = 0; i < entries.length; i++)
		{
			StubMenu child = new StubMenu(200, 30, 100, 34);
			child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
			entries[i] = new StubMenuEntry("Player", child);
		}
		StubMenu extra = new StubMenu(300, 30, 100, 34);
		extra.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Leaf", null)});
		entries[0].getSubMenu().setMenuEntries(new MenuEntry[]{new StubMenuEntry("Nested", extra)});
		root.setMenuEntries(entries);
		assertTreeError(root, "menu count limit exceeded nodes=513 limit=513");
		StubMenu tooManyRows = new StubMenu(10, 0, 160, 200);
		MenuEntry[] wideEntries = new MenuEntry[10];
		for (int i = 0; i < wideEntries.length; i++)
		{
			StubMenu child = new StubMenu(200, 30, 100, 200);
			MenuEntry[] childEntries = new MenuEntry[512];
			for (int j = 0; j < childEntries.length; j++)
			{
				childEntries[j] = new StubMenuEntry("Action", null);
			}
			child.setMenuEntries(childEntries);
			wideEntries[i] = new StubMenuEntry("Child", child);
		}
		tooManyRows.setMenuEntries(wideEntries);
		assertTreeError(tooManyRows, "menu total entry count limit exceeded");
		StubMenu cycle = new StubMenu(10, 0, 160, 49);
		cycle.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Self", cycle)});
		assertTreeError(cycle, "menu identity cycle or reuse");
	}

	@Test
	public void excessiveVisibleCandidateAreaFallsBackBeforeNativeDraw()
	{
		StubMenu root = new StubMenu(10, 0, 160, 79);
		MenuEntry[] entries = new MenuEntry[4];
		for (int i = 0; i < entries.length; i++)
		{
			StubMenu child = new StubMenu(0, 0, 900, 600);
			child.setMenuEntries(new MenuEntry[]{new StubMenuEntry("Action", null)});
			entries[i] = new StubMenuEntry("Parent", child);
		}
		root.setMenuEntries(entries);
		int[] pixels = new int[900 * 600];
		boolean[] called = {false};
		NativeSubmenuProbe.Result result = NativeSubmenuProbe.probePixels(
			pixels, 900, 600, SubmenuTree.capture(root), () -> called[0] = true);
		assertEquals(NativeSubmenuProbe.State.UNSUPPORTED, result.state);
		assertTrue(result.reason.contains("candidate pixel area limit exceeded"));
		assertFalse(called[0]);
		assertTrue(Arrays.equals(new int[pixels.length], pixels));
	}

	private static void assertTreeError(StubMenu root, String expected)
	{
		try
		{
			SubmenuTree.capture(root);
			throw new AssertionError("expected " + expected);
		}
		catch (IllegalArgumentException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains(expected));
		}
	}

	@Test
	public void childHeaderAndTransparentOverlapUseSharedRenderer()
	{
		MenuRenderer renderer = new MenuRenderer();
		RightClickStylerConfig config = new RightClickStylerConfig() { };
		Theme glass = BuiltInThemes.find("glass").withShowHeader(false)
			.withShowBorder(false).withShowShadow(false).withRadius(0).withTextShadow(false);
		BufferedImage layer = new BufferedImage(140, 110, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = layer.createGraphics();
		try
		{
			renderer.render(g, null, glass, config,
				Collections.singletonList(new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.OTHER)),
				new Rectangle(10, 10, 80, 70), -1, 0);
			renderer.render(g, null, glass, config,
				Collections.singletonList(new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.OTHER)),
				new Rectangle(40, 30, 80, 70), -1, 0, "Follow");
		}
		finally
		{
			g.dispose();
		}
		int overlapAlpha = layer.getRGB(50, 60) >>> 24;
		int childOnlyAlpha = layer.getRGB(105, 60) >>> 24;
		assertTrue(overlapAlpha > childOnlyAlpha);
		assertTrue(childOnlyAlpha > 0 && childOnlyAlpha < 255);

		Theme headerTheme = BuiltInThemes.find("classic");
		BufferedImage named = new BufferedImage(140, 110, BufferedImage.TYPE_INT_ARGB);
		BufferedImage standard = new BufferedImage(140, 110, BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < 2; i++)
		{
			Graphics2D headerGraphics = (i == 0 ? named : standard).createGraphics();
			try
			{
				renderer.render(headerGraphics, null, headerTheme, config, Collections.emptyList(),
					new Rectangle(10, 10, 100, 34), -1, 0, i == 0 ? "Follow" : "Choose Option");
			}
			finally
			{
				headerGraphics.dispose();
			}
		}
		boolean different = false;
		for (int y = 10; y < 29; y++)
		{
			for (int x = 10; x < 110; x++)
			{
				different |= named.getRGB(x, y) != standard.getRGB(x, y);
			}
		}
		assertTrue(different);
	}

	@Test
	public void allBuiltInThemesRenderParentAndChild()
	{
		RightClickStylerConfig config = new RightClickStylerConfig() { };
		for (Theme theme : BuiltInThemes.ALL)
		{
			MenuRenderer renderer = new MenuRenderer();
			BufferedImage image = new BufferedImage(180, 110, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			try
			{
				MenuRenderer.Row parent = new MenuRenderer.Row("Options", "", MenuRenderer.TargetKind.OTHER);
				parent.set("Options", "", MenuRenderer.TargetKind.OTHER, true, false);
				renderer.render(g, null, theme, config, Collections.singletonList(parent),
					new Rectangle(10, 10, 80, 49), 0, 0);
				renderer.render(g, null, theme, config,
					Collections.singletonList(new MenuRenderer.Row("Follow", "", MenuRenderer.TargetKind.OTHER)),
					new Rectangle(90, 20, 80, 49), 0, 0, "Options");
			}
			finally
			{
				g.dispose();
			}
			assertTrue(theme.name, (image.getRGB(40, 40) >>> 24) > 0);
			assertTrue(theme.name, (image.getRGB(120, 50) >>> 24) > 0);
		}
	}

	@Test
	public void targetOnlyPlayerTextSharesOriginWithOptionRowsInRootAndChild()
	{
		String player = "yr651 (level-49) >";
		for (int menuX : new int[]{10, 70})
		{
			BufferedImage option = renderSingleRow(menuX, player, "", false);
			BufferedImage target = renderSingleRow(menuX, "",
				"<col=ff00ff>  " + player + "</col>", true);
			BufferedImage taggedOption = renderSingleRow(menuX, "<col=ffffff></col>",
				"<col=ff00ff>" + player + "</col>", true);
			BufferedImage whitespaceOption = renderSingleRow(menuX, "<col=ffffff>  </col>",
				"<col=ff00ff>" + player + "</col>", true);
			assertEquals(firstTextX(option, menuX), firstTextX(target, menuX));
			assertEquals(firstTextX(option, menuX), firstTextX(taggedOption, menuX));
			assertEquals(firstTextX(option, menuX), firstTextX(whitespaceOption, menuX));
		}
	}

	@Test
	public void optionAndTargetStillHaveAVisibleGap()
	{
		BufferedImage option = renderSingleRow(10, "Follow", "", false);
		BufferedImage combined = renderSingleRow(10, "Follow", "yr651 (level-49)", false);
		int optionEnd = lastTextX(option, 10);
		int targetStart = firstDifferenceX(option, combined, 10);
		assertTrue("target must follow the option", targetStart > optionEnd);
		assertTrue("gap should remain modest", targetStart - optionEnd < 20);
	}

	@Test
	public void longFormattedChildHeadersStayInsideNativeBoundsAtBothCanvasEdges()
	{
		Theme theme = BuiltInThemes.find("classic").withShowBorder(false)
			.withShowShadow(false).withRadius(0).withTextShadow(false).withOpacity(100)
			.withHeader(Color.BLACK).withHeaderText(Color.WHITE);
		RightClickStylerConfig config = new RightClickStylerConfig() { };
		for (int childX : new int[]{0, 115})
		{
			Rectangle childBounds = new Rectangle(childX, 10, 85, 49);
			BufferedImage empty = renderChildSample(theme, config, childBounds, "", "");
			BufferedImage tagged = renderChildSample(theme, config, childBounds,
				"<col=ff0000>Very long item name (level-126)</col>", "");
			BufferedImage optionFallback = renderChildSample(theme, config, childBounds,
				"Inventory tag", "");
			assertTrue("formatted header should retain its colour", hasRedText(tagged,
				childX + 4, childX + 81, 10, 29));
			assertTrue("an empty target should show a bounded option header",
				countDifferences(empty, optionFallback, childX + 4, childX + 81, 10, 29) > 0);
			assertEquals("header must not paint into the right inset", 0,
				countDifferences(empty, tagged, childX + 81, childX + 85, 10, 29));
			assertEquals("option fallback must not paint into the right inset", 0,
				countDifferences(empty, optionFallback, childX + 81, childX + 85, 10, 29));
			assertEquals("header must not paint outside native bounds", 0,
				countDifferences(empty, tagged, childX + 85, 200, 10, 29));
		}
	}

	@Test
	public void longFormattedChildLabelsKeepArrowsAndTextInsideNativeBounds()
	{
		Theme theme = BuiltInThemes.find("classic").withShowBorder(false)
			.withShowShadow(false).withRadius(0).withTextShadow(false).withOpacity(100);
		RightClickStylerConfig config = new RightClickStylerConfig() { };
		for (boolean opensLeft : new boolean[]{false, true})
		{
			Rectangle bounds = new Rectangle(opensLeft ? 0 : 115, 10, 85, 49);
			BufferedImage empty = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
			BufferedImage labeled = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
			MenuRenderer.Row blank = new MenuRenderer.Row("", "", MenuRenderer.TargetKind.OTHER);
			MenuRenderer.Row row = new MenuRenderer.Row("Color",
				"<col=00ff00>Very long item name</col>", MenuRenderer.TargetKind.OTHER);
			blank.set("", "", MenuRenderer.TargetKind.OTHER, true, opensLeft);
			row.set("Color", "<col=00ff00>Very long item name</col>",
				MenuRenderer.TargetKind.OTHER, true, opensLeft);
			for (int i = 0; i < 2; i++)
			{
				Graphics2D graphics = (i == 0 ? empty : labeled).createGraphics();
				try
				{
					new MenuRenderer().render(graphics, null, theme, config,
						Collections.singletonList(i == 0 ? blank : row), bounds, -1, 0, "Item");
				}
				finally
				{
					graphics.dispose();
				}
			}
			assertTrue("row text should be visible", countDifferences(empty, labeled,
				bounds.x, bounds.x + bounds.width, 29, 44) > 0);
			int reserved = opensLeft ? 4 : 17;
			assertEquals("row text must respect the native arrow/inset area", 0,
				countDifferences(empty, labeled, bounds.x + bounds.width - reserved,
					bounds.x + bounds.width, 29, 44));
			assertEquals("row text must not extend outside native bounds", 0,
				countDifferences(empty, labeled, bounds.x + bounds.width,
					200, 29, 44));
		}
	}

	private static BufferedImage renderChildSample(Theme theme, RightClickStylerConfig config,
		Rectangle bounds, String header, String option)
	{
		BufferedImage image = new BufferedImage(200, 80, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, config,
				Collections.singletonList(new MenuRenderer.Row(option, "", MenuRenderer.TargetKind.OTHER)),
				bounds, -1, 0, header);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static int countDifferences(BufferedImage first, BufferedImage second,
		int startX, int endX, int startY, int endY)
	{
		int count = 0;
		for (int y = startY; y < endY; y++)
		{
			for (int x = startX; x < endX; x++)
			{
				if (first.getRGB(x, y) != second.getRGB(x, y))
				{
					count++;
				}
			}
		}
		return count;
	}

	private static boolean hasRedText(BufferedImage image, int startX, int endX,
		int startY, int endY)
	{
		for (int y = startY; y < endY; y++)
		{
			for (int x = startX; x < endX; x++)
			{
				Color pixel = new Color(image.getRGB(x, y), true);
				if (pixel.getRed() > pixel.getGreen() + 50 && pixel.getRed() > pixel.getBlue() + 50)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static BufferedImage renderSingleRow(int menuX, String option, String target, boolean submenu)
	{
		Theme theme = BuiltInThemes.find("classic").withShowHeader(false)
			.withShowBorder(false).withShowShadow(false).withRadius(0)
			.withTextShadow(false).withOpacity(100);
		BufferedImage image = new BufferedImage(260, 80, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			MenuRenderer.Row row = new MenuRenderer.Row(option, target, MenuRenderer.TargetKind.PLAYER);
			row.set(option, target, MenuRenderer.TargetKind.PLAYER, submenu, false);
			new MenuRenderer().render(graphics, null, theme, new RightClickStylerConfig() { },
				Collections.singletonList(row), new Rectangle(menuX, 10, 170, 49), -1, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	private static int firstTextX(BufferedImage image, int menuX)
	{
		int background = image.getRGB(menuX + 150, 31);
		for (int x = menuX; x < menuX + 100; x++)
		{
			for (int y = 30; y < 44; y++)
			{
				if (image.getRGB(x, y) != background)
				{
					return x - menuX;
				}
			}
		}
		return -1;
	}

	private static int lastTextX(BufferedImage image, int menuX)
	{
		int background = image.getRGB(menuX + 150, 31);
		for (int x = menuX + 99; x >= menuX; x--)
		{
			for (int y = 30; y < 44; y++)
			{
				if (image.getRGB(x, y) != background)
				{
					return x - menuX;
				}
			}
		}
		return -1;
	}

	private static int firstDifferenceX(BufferedImage first, BufferedImage second, int menuX)
	{
		for (int x = menuX; x < menuX + 100; x++)
		{
			for (int y = 30; y < 44; y++)
			{
				if (first.getRGB(x, y) != second.getRGB(x, y))
				{
					return x - menuX;
				}
			}
		}
		return -1;
	}

	private static void paint(int[] pixels, int width, int height, StubMenu... menus)
	{
		for (StubMenu menu : menus)
		{
			Rectangle rect = new Rectangle(menu.x, menu.y, menu.width, menu.height)
				.intersection(new Rectangle(0, 0, width, height));
			for (int y = rect.y; y < rect.y + rect.height; y++)
			{
				for (int x = rect.x; x < rect.x + rect.width; x++)
				{
					pixels[y * width + x] = Color.GRAY.getRGB();
				}
			}
		}
	}

	private static final class StubMenu implements Menu
	{
		private int x;
		private int y;
		private int width;
		private int height;
		private MenuEntry[] entries = new MenuEntry[0];

		private StubMenu(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		private void setBounds(int x, int y, int width, int height)
		{
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		@Override
		public MenuEntry createMenuEntry(int idx)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public MenuEntry[] getMenuEntries()
		{
			return entries;
		}

		@Override
		public void setMenuEntries(MenuEntry[] entries)
		{
			this.entries = entries;
		}

		@Override
		public void removeMenuEntry(MenuEntry entry)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public int getMenuX()
		{
			return x;
		}

		@Override
		public int getMenuY()
		{
			return y;
		}

		@Override
		public int getMenuWidth()
		{
			return width;
		}

		@Override
		public int getMenuHeight()
		{
			return height;
		}
	}

	private static final class StubMenuEntry implements MenuEntry
	{
		private MenuAction type;
		private int itemId;
		private int identifier;
		private final Menu submenu;
		private String option;
		private String target = "";

		private StubMenuEntry(MenuAction type, int itemId, Menu submenu)
		{
			this.type = type;
			this.itemId = itemId;
			this.submenu = submenu;
			this.option = "";
		}

		private StubMenuEntry(String option, Menu submenu)
		{
			this(MenuAction.CANCEL, -1, submenu);
			this.option = option;
		}

		@Override
		public String getOption()
		{
			return option;
		}

		@Override
		public MenuEntry setOption(String option)
		{
			this.option = option;
			return this;
		}

		@Override
		public String getTarget()
		{
			return target;
		}

		@Override
		public MenuEntry setTarget(String target)
		{
			this.target = target;
			return this;
		}

		@Override
		public int getIdentifier()
		{
			return identifier;
		}

		@Override
		public MenuEntry setIdentifier(int identifier)
		{
			this.identifier = identifier;
			return this;
		}

		@Override
		public MenuAction getType()
		{
			return type;
		}

		@Override
		public MenuEntry setType(MenuAction type)
		{
			this.type = type;
			return this;
		}

		@Override
		public int getParam0()
		{
			return 0;
		}

		@Override
		public MenuEntry setParam0(int param0)
		{
			return this;
		}

		@Override
		public int getParam1()
		{
			return 0;
		}

		@Override
		public MenuEntry setParam1(int param1)
		{
			return this;
		}

		@Override
		public boolean isForceLeftClick()
		{
			return false;
		}

		@Override
		public MenuEntry setForceLeftClick(boolean forceLeftClick)
		{
			return this;
		}

		@Override
		public int getWorldViewId()
		{
			return 0;
		}

		@Override
		public MenuEntry setWorldViewId(int worldViewId)
		{
			return this;
		}

		@Override
		public boolean isDeprioritized()
		{
			return false;
		}

		@Override
		public MenuEntry setDeprioritized(boolean deprioritized)
		{
			return this;
		}

		@Override
		public MenuEntry onClick(Consumer<MenuEntry> consumer)
		{
			return this;
		}

		@Override
		public Consumer<MenuEntry> onClick()
		{
			return null;
		}

		@Override
		public boolean isItemOp()
		{
			return false;
		}

		@Override
		public int getItemOp()
		{
			return 0;
		}

		@Override
		public int getItemId()
		{
			return itemId;
		}

		@Override
		public MenuEntry setItemId(int itemId)
		{
			this.itemId = itemId;
			return this;
		}

		@Override
		public Widget getWidget()
		{
			return null;
		}

		@Override
		public NPC getNpc()
		{
			return null;
		}

		@Override
		public Player getPlayer()
		{
			return null;
		}

		@Override
		public Actor getActor()
		{
			return null;
		}

		@Override
		public Menu getSubMenu()
		{
			return submenu;
		}

		@Override
		public Menu createSubMenu()
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void deleteSubMenu()
		{
			throw new UnsupportedOperationException();
		}
	}
}
