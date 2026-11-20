package com.rightclickstyler;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.Text;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A submenu row's layout must not depend on which way its child opens. A closed child has no
 * geometry (zero width), so its row reads as right-opening; once hovered at the canvas's right
 * edge the child opens left. Text that followed the opening side moved 16px on hover, and the
 * widest row was horizontally compressed, which RuneScape fonts render visibly narrower with a
 * wider option/target gap.
 */
public class SubmenuDirectionLayoutTest
{
	private static final String MEDALLION = "<col=ff9040>Drakan's medallion</col>";
	private static final Color ITEM_COLOUR = new Color(0xFF9040);
	private static final int CANVAS_WIDTH = 760;
	private static final int MENU_Y = 30;
	private static final int ARROW_RESERVE = 16;

	// Top-down, as drawn. The README's styled submenu menu, with Menu Entry Swapper and Inventory Tags.
	private static final String[][] SPEC = {
		{"Drop", MEDALLION, ""}, {"Wear", MEDALLION, ""}, {"Teleport", MEDALLION, "sub"},
		{"Use", MEDALLION, ""}, {"Examine", MEDALLION, ""}, {"Inventory tag", MEDALLION, "sub"},
		{"Swap left-click", MEDALLION, "sub"}, {"Swap shift-click", MEDALLION, "sub"},
		{"Interface Origin", "", "sub"}, {"Cancel", "", ""}
	};
	private static final int INVENTORY_TAG = 5;
	/** The widest row, which defines the native width. */
	private static final int SWAP_SHIFT_CLICK = 7;
	private static final int[] SUBMENU_ROWS = {2, 5, 6, 7, 8};

	private static final RightClickStylerConfig CONFIG = new RightClickStylerConfig() {};

	@Test
	public void textAndArrowPixelsDoNotDependOnOpeningDirection()
	{
		Rectangle bounds = nativeBounds(SPEC);
		for (Theme theme : themes(true))
		{
			for (int offset : new int[]{Limits.MIN_TEXT_OFFSET_X, 3, Limits.MAX_TEXT_OFFSET_X})
			{
				RightClickStylerConfig config = new RightClickStylerConfig()
				{
					@Override
					public int textOffsetX()
					{
						return offset;
					}
				};
				for (int hover : new int[]{-1, SWAP_SHIFT_CLICK, INVENTORY_TAG})
				{
					String label = label(theme) + " offset=" + offset + " hover=" + hover;
					assertImagesEqual(label,
						render(theme, config, rows(SPEC, false, -1), bounds, hover),
						render(theme, config, rows(SPEC, true, -1), bounds, hover));
				}
			}
		}
	}

	@Test
	public void hoveringDoesNotMoveOrScaleSubmenuText()
	{
		Rectangle bounds = nativeBounds(SPEC);
		for (Theme theme : themes(false))
		{
			for (boolean opensLeft : new boolean[]{false, true})
			{
				for (int index : new int[]{SWAP_SHIFT_CLICK, INVENTORY_TAG})
				{
					String label = label(theme) + " opensLeft=" + opensLeft + " row=" + SPEC[index][0];
					boolean[][] idle = textMask(theme, rows(SPEC, opensLeft, -1),
						rows(SPEC, opensLeft, index), bounds, -1, index);
					// Hovering a row opens its child, which is when the opening side becomes known.
					boolean[][] hovered = textMask(theme, rows(SPEC, true, -1),
						rows(SPEC, true, index), bounds, index, index);
					assertMasksEqual(label, idle, hovered);
					assertEquals(label + " text start", textOffsetColumn(), firstColumn(hovered), 1);
				}
			}
		}
	}

	@Test
	public void widestSubmenuRowKeepsNaturalProportionsInEitherDirection()
	{
		Rectangle bounds = nativeBounds(SPEC);
		Rectangle wide = new Rectangle(bounds.x - 100, bounds.y, bounds.width + 100, bounds.height);
		for (HoverStyle hover : HoverStyle.values())
		{
			Theme theme = theme(FontChoice.RS_BOLD, 12, hover);
			for (boolean opensLeft : new boolean[]{false, true})
			{
				for (int hoverIndex : new int[]{-1, SWAP_SHIFT_CLICK})
				{
					String label = label(theme) + " opensLeft=" + opensLeft + " hover=" + hoverIndex;
					boolean[][] fitted = textMask(theme, rows(SPEC, opensLeft, -1),
						rows(SPEC, opensLeft, SWAP_SHIFT_CLICK), bounds, hoverIndex, SWAP_SHIFT_CLICK);
					boolean[][] natural = textMask(theme, rows(SPEC, opensLeft, -1),
						rows(SPEC, opensLeft, SWAP_SHIFT_CLICK), wide, hoverIndex, SWAP_SHIFT_CLICK);
					assertMasksEqual(label + " native width renders uncompressed",
						fitted, trimColumns(natural, bounds.width));
				}
			}
		}
	}

	@Test
	public void overlongSubmenuRowsStillFitInsideTheTextBoxInEitherDirection()
	{
		String[][] spec = {
			{"Swap shift-click", "<col=ff9040>Drakan's medallion of the long and winding name</col>", "sub"},
			{"Cancel", "", ""}
		};
		Rectangle bounds = new Rectangle(CANVAS_WIDTH - 120, MENU_Y, 120,
			MenuRenderer.HEADER_HEIGHT + MenuRenderer.ROW_HEIGHT * spec.length);
		for (Theme theme : themes(false))
		{
			for (int hover : new int[]{-1, 0})
			{
				String label = label(theme) + " hover=" + hover;
				BufferedImage right = render(theme, CONFIG, rows(spec, false, -1), bounds, hover);
				BufferedImage left = render(theme, CONFIG, rows(spec, true, -1), bounds, hover);
				assertImagesEqual(label, right, left);
				boolean[][] text = textMask(theme, rows(spec, true, -1), rows(spec, true, 0), bounds, hover, 0);
				assertTrue(label + " target end remains visible",
					hasColour(left, ITEM_COLOUR, bounds.x, bounds.x + bounds.width - ARROW_RESERVE, rowY(bounds, 0)));
				assertTrue(label + " text drawn", firstColumn(text) >= 0);
				assertTrue(label + " text stays out of the arrow reserve, last=" + lastColumn(text),
					lastColumn(text) < bounds.width - ARROW_RESERVE);
				assertNothingOutside(label, left, bounds);
			}
		}
	}

	@Test
	public void closedAndOpenChildrenGiveTheParentRowTheSameLayout()
	{
		Rectangle bounds = nativeBounds(SPEC);
		SubmenuTree closed = SubmenuTree.capture(rootMenu(bounds, -1));
		SubmenuTree open = SubmenuTree.capture(rootMenu(bounds, SWAP_SHIFT_CLICK));
		int entryIndex = SPEC.length - 1 - SWAP_SHIFT_CLICK;
		assertFalse("a closed, zero-width child reads as right-opening",
			closed.nodes.get(0).childOpensLeft[entryIndex]);
		assertTrue("the open child at the canvas edge reads as left-opening",
			open.nodes.get(0).childOpensLeft[entryIndex]);
		assertEquals("the zero-width child is still captured", 0, childOf(closed, entryIndex).bounds.width);
		assertTrue("the open child lies left of the root",
			childOf(open, entryIndex).bounds.x + childOf(open, entryIndex).bounds.width <= bounds.x);

		List<MenuRenderer.Row> closedRows = new ArrayList<>();
		List<MenuRenderer.Row> openRows = new ArrayList<>();
		MenuOverlay.fillMenuRows(closed.nodes.get(0), null, closedRows, new ArrayList<>());
		MenuOverlay.fillMenuRows(open.nodes.get(0), null, openRows, new ArrayList<>());
		for (Theme theme : themes(true))
		{
			for (int hover : new int[]{-1, SWAP_SHIFT_CLICK})
			{
				assertImagesEqual(label(theme) + " hover=" + hover,
					render(theme, CONFIG, closedRows, bounds, hover),
					render(theme, CONFIG, openRows, bounds, hover));
			}
		}
	}

	@Test
	public void submenuArrowsStayVisibleOnTheRightAndSeparatedFromText()
	{
		Rectangle bounds = nativeBounds(SPEC);
		for (Theme theme : themes(false))
		{
			for (boolean opensLeft : new boolean[]{false, true})
			{
				for (int hover : new int[]{-1, SWAP_SHIFT_CLICK})
				{
					for (int index : SUBMENU_ROWS)
					{
						String label = label(theme) + " opensLeft=" + opensLeft + " hover=" + hover
							+ " row=" + SPEC[index][0];
						boolean[][] arrow = arrowMask(theme, opensLeft, bounds, hover, index);
						int first = firstColumn(arrow);
						int last = lastColumn(arrow);
						assertTrue(label + " arrow visible", first >= 0);
						assertTrue(label + " arrow on the right, columns " + first + ".." + last,
							first >= bounds.width - ARROW_RESERVE && last < bounds.width);
						boolean[][] text = textMask(theme, rows(SPEC, opensLeft, -1),
							rows(SPEC, opensLeft, index), bounds, hover, index);
						assertTrue(label + " text clear of arrow, text ends " + lastColumn(text)
							+ " arrow starts " + first, lastColumn(text) < bounds.width - ARROW_RESERVE);
						assertTrue(label + " gap", first - lastColumn(text) - 1 >= ARROW_RESERVE - 9);
					}
				}
			}
		}
	}

	// ---- fixtures

	private static SubmenuTree.Node childOf(SubmenuTree tree, int parentEntryIndex)
	{
		for (SubmenuTree.Node node : tree.nodes)
		{
			if (node.parentNode == 0 && node.parentEntryIndex == parentEntryIndex)
			{
				return node;
			}
		}
		throw new AssertionError("no child captured for root entry " + parentEntryIndex);
	}

	private static List<Theme> themes(boolean builtInDecorations)
	{
		List<Theme> themes = new ArrayList<>();
		for (FontChoice font : FontChoice.values())
		{
			int[] sizes = font.isScalable()
				? new int[]{Limits.MIN_FONT_SIZE, Limits.MAX_FONT_SIZE} : new int[]{12};
			for (int size : sizes)
			{
				for (HoverStyle hover : HoverStyle.values())
				{
					Theme theme = BuiltInThemes.find("dark").withFont(font).withFontSize(size)
						.withHoverStyle(hover);
					themes.add(builtInDecorations ? theme : plain(theme));
				}
			}
		}
		return themes;
	}

	private static Theme theme(FontChoice font, int size, HoverStyle hover)
	{
		return plain(BuiltInThemes.find("dark").withFont(font).withFontSize(size).withHoverStyle(hover));
	}

	/** Solid, square and undecorated, so text and arrow pixels can be isolated by difference. */
	private static Theme plain(Theme theme)
	{
		return theme.withOpacity(100).withHoverOpacity(100).withRadius(0).withShowBorder(false)
			.withShowShadow(false).withTextShadow(false);
	}

	private static String label(Theme theme)
	{
		return theme.font + "/" + theme.fontSize + "/" + theme.hoverStyle;
	}

	/**
	 * The game's width for this menu: its widest row in its own bold font, with 12px for a submenu
	 * arrow, plus 8px. This reproduces the captured live root ("Swap shift-click Saw", 135px, in a
	 * 155px menu). The menu sits flush with the canvas's right edge, so every child opens left.
	 */
	private static Rectangle nativeBounds(String[][] spec)
	{
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scratch.createGraphics();
		try
		{
			FontMetrics metrics = graphics.getFontMetrics(FontManager.getRunescapeBoldFont());
			int widest = metrics.stringWidth("Choose Option");
			for (String[] row : spec)
			{
				String plain = Text.removeTags(row[0] + " " + row[1]).trim();
				widest = Math.max(widest, metrics.stringWidth(plain) + (row[2].isEmpty() ? 0 : 12));
			}
			int width = widest + 8;
			return new Rectangle(CANVAS_WIDTH - width, MENU_Y, width,
				MenuRenderer.HEADER_HEIGHT + MenuRenderer.ROW_HEIGHT * spec.length);
		}
		finally
		{
			graphics.dispose();
		}
	}

	/**
	 * @param opensLeft the opening side every submenu row reports
	 * @param blank a row drawn without option or target (same arrow), or -1
	 */
	private static List<MenuRenderer.Row> rows(String[][] spec, boolean opensLeft, int blank)
	{
		return rows(spec, opensLeft, blank, false);
	}

	private static List<MenuRenderer.Row> rows(String[][] spec, boolean opensLeft, int blank, boolean dropArrow)
	{
		List<MenuRenderer.Row> rows = new ArrayList<>();
		for (int i = 0; i < spec.length; i++)
		{
			boolean sub = !spec[i][2].isEmpty() && !(dropArrow && i == blank);
			MenuRenderer.Row row = new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.ITEM);
			row.set(i == blank ? "" : spec[i][0], i == blank ? "" : spec[i][1],
				MenuRenderer.TargetKind.ITEM, sub, sub && opensLeft);
			rows.add(row);
		}
		return rows;
	}

	private static BufferedImage render(Theme theme, RightClickStylerConfig config, List<MenuRenderer.Row> rows,
		Rectangle bounds, int hover)
	{
		BufferedImage image = new BufferedImage(CANVAS_WIDTH + 10, bounds.y + bounds.height + 10,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, null, theme, config, rows, bounds, hover, 0);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	/** Pixels that only the row's option and target contribute, relative to the menu's left edge. */
	private static boolean[][] textMask(Theme theme, List<MenuRenderer.Row> rows, List<MenuRenderer.Row> blanked,
		Rectangle bounds, int hover, int index)
	{
		return difference(render(theme, CONFIG, rows, bounds, hover),
			render(theme, CONFIG, blanked, bounds, hover), bounds, index);
	}

	/** Pixels that only the row's submenu arrow contributes. */
	private static boolean[][] arrowMask(Theme theme, boolean opensLeft, Rectangle bounds, int hover, int index)
	{
		return difference(render(theme, CONFIG, rows(SPEC, opensLeft, index), bounds, hover),
			render(theme, CONFIG, rows(SPEC, opensLeft, index, true), bounds, hover), bounds, index);
	}

	private static boolean[][] difference(BufferedImage a, BufferedImage b, Rectangle bounds, int index)
	{
		int top = rowY(bounds, index);
		boolean[][] mask = new boolean[MenuRenderer.ROW_HEIGHT][bounds.width];
		for (int y = 0; y < MenuRenderer.ROW_HEIGHT; y++)
		{
			for (int x = 0; x < bounds.width; x++)
			{
				mask[y][x] = a.getRGB(bounds.x + x, top + y) != b.getRGB(bounds.x + x, top + y);
			}
		}
		return mask;
	}

	private static int rowY(Rectangle bounds, int index)
	{
		return bounds.y + MenuRenderer.HEADER_HEIGHT + index * MenuRenderer.ROW_HEIGHT;
	}

	private static int textOffsetColumn()
	{
		return Limits.textOffsetX(CONFIG);
	}

	private static boolean[][] trimColumns(boolean[][] mask, int width)
	{
		boolean[][] trimmed = new boolean[mask.length][];
		for (int y = 0; y < mask.length; y++)
		{
			trimmed[y] = Arrays.copyOf(mask[y], width);
			for (int x = width; x < mask[y].length; x++)
			{
				if (mask[y][x])
				{
					fail("natural row is wider than the native text box at column " + x);
				}
			}
		}
		return trimmed;
	}

	private static int firstColumn(boolean[][] mask)
	{
		int first = Integer.MAX_VALUE;
		for (boolean[] row : mask)
		{
			for (int x = 0; x < row.length; x++)
			{
				if (row[x])
				{
					first = Math.min(first, x);
					break;
				}
			}
		}
		return first == Integer.MAX_VALUE ? -1 : first;
	}

	private static int lastColumn(boolean[][] mask)
	{
		int last = -1;
		for (boolean[] row : mask)
		{
			for (int x = row.length - 1; x > last; x--)
			{
				if (row[x])
				{
					last = x;
					break;
				}
			}
		}
		return last;
	}

	private static boolean hasColour(BufferedImage image, Color colour, int fromX, int toX, int top)
	{
		for (int y = top; y < top + MenuRenderer.ROW_HEIGHT; y++)
		{
			for (int x = fromX; x < toX; x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == (colour.getRGB() & 0xFFFFFF))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static void assertNothingOutside(String label, BufferedImage image, Rectangle bounds)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (!bounds.contains(x, y) && image.getRGB(x, y) != 0)
				{
					fail(label + " painted outside the native rectangle at " + x + "," + y);
				}
			}
		}
	}

	private static void assertImagesEqual(String label, BufferedImage expected, BufferedImage actual)
	{
		for (int y = 0; y < expected.getHeight(); y++)
		{
			for (int x = 0; x < expected.getWidth(); x++)
			{
				if (expected.getRGB(x, y) != actual.getRGB(x, y))
				{
					fail(label + " differs at " + x + "," + y);
				}
			}
		}
	}

	private static void assertMasksEqual(String label, boolean[][] expected, boolean[][] actual)
	{
		assertTrue(label + " has text", firstColumn(expected) >= 0);
		for (int y = 0; y < expected.length; y++)
		{
			for (int x = 0; x < expected[y].length; x++)
			{
				if (expected[y][x] != actual[y][x])
				{
					fail(label + " text pixels differ at column " + x + " row " + y
						+ " (expected columns " + firstColumn(expected) + ".." + lastColumn(expected)
						+ ", actual " + firstColumn(actual) + ".." + lastColumn(actual) + ")");
				}
			}
		}
	}

	/**
	 * The menu above as the client exposes it. Every submenu child is closed (zero width) except
	 * {@code openRow}, whose child is open to the left of the root.
	 */
	private static Menu rootMenu(Rectangle bounds, int openRow)
	{
		MenuEntry[] entries = new MenuEntry[SPEC.length];
		for (int i = 0; i < SPEC.length; i++)
		{
			Menu child = null;
			if (!SPEC[i][2].isEmpty())
			{
				MenuEntry[] leaf = {entry("Leaf", "", null)};
				child = i == openRow
					? menu(new Rectangle(bounds.x - 90, bounds.y + 60, 90, 34), leaf)
					: menu(new Rectangle(0, 0, 0, 0), leaf);
			}
			// Entries are stored bottom-up.
			entries[SPEC.length - 1 - i] = entry(SPEC[i][0], SPEC[i][1], child);
		}
		return menu(bounds, entries);
	}

	private static Menu menu(Rectangle bounds, MenuEntry[] entries)
	{
		return (Menu) Proxy.newProxyInstance(Menu.class.getClassLoader(), new Class<?>[]{Menu.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getMenuX":
						return bounds.x;
					case "getMenuY":
						return bounds.y;
					case "getMenuWidth":
						return bounds.width;
					case "getMenuHeight":
						return bounds.height;
					case "getMenuEntries":
						return entries;
					default:
						return objectMethod(proxy, method.getName(), args, method.getReturnType());
				}
			});
	}

	private static MenuEntry entry(String option, String target, Menu subMenu)
	{
		return (MenuEntry) Proxy.newProxyInstance(MenuEntry.class.getClassLoader(), new Class<?>[]{MenuEntry.class},
			(proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getOption":
						return option;
					case "getTarget":
						return target;
					case "getSubMenu":
						return subMenu;
					case "getType":
						return MenuAction.RUNELITE;
					case "getItemId":
						return -1;
					default:
						return objectMethod(proxy, method.getName(), args, method.getReturnType());
				}
			});
	}

	private static Object objectMethod(Object proxy, String name, Object[] args, Class<?> returnType)
	{
		switch (name)
		{
			case "equals":
				return proxy == args[0];
			case "hashCode":
				return System.identityHashCode(proxy);
			case "toString":
				return "stub";
			default:
				break;
		}
		if (!returnType.isPrimitive() || returnType == void.class)
		{
			return null;
		}
		if (returnType == boolean.class)
		{
			return false;
		}
		if (returnType == long.class)
		{
			return 0L;
		}
		if (returnType == float.class)
		{
			return 0f;
		}
		if (returnType == double.class)
		{
			return 0d;
		}
		if (returnType == char.class)
		{
			return (char) 0;
		}
		if (returnType == byte.class)
		{
			return (byte) 0;
		}
		if (returnType == short.class)
		{
			return (short) 0;
		}
		return 0;
	}
}
