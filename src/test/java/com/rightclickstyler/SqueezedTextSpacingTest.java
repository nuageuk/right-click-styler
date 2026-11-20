package com.rightclickstyler;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.Text;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The game sizes its menu with its own font, so a row or header measured with the selected font can
 * be a few pixels too wide and is fitted by a horizontal scale. The RuneScape fonts' hinting snapped
 * scaled glyphs to the next whole pixel size, drawing them about 6% narrower while each run still
 * started at its scaled position: a 1px fit turned "Teleport Drakan's medallion" into
 * "Teleport    Drakan's medallion". A fitted RuneScape-font row must keep its natural spacing.
 */
public class SqueezedTextSpacingTest
{
	private static final String MEDALLION = "<col=ff9040>Drakan's medallion</col>";
	private static final int ICON_COLOUR = 0x3366FF;
	private static final FontChoice[] RS_FONTS = {FontChoice.RS_REGULAR, FontChoice.RS_SMALL, FontChoice.RS_BOLD};
	private static final RightClickStylerConfig PLAIN = new RightClickStylerConfig() {};
	private static final RightClickStylerConfig HIGHLIGHT = new RightClickStylerConfig()
	{
		@Override
		public boolean highlightsEnabled()
		{
			return true;
		}

		@Override
		public boolean highlightsBold()
		{
			return true;
		}

		@Override
		public String highlightRules()
		{
			return "Teleport=#FF5C5C";
		}
	};

	/** Option, target, whether it is a submenu row, config, label. */
	private static final Object[][] ENTRIES = {
		{"Teleport", MEDALLION, true, PLAIN, "submenu row"},
		{"Swap shift-click", MEDALLION, true, PLAIN, "widest README row"},
		{"Use", "<col=ff9040>Drakan's medallion<col=dfab23> (ID: 22400)", false, PLAIN, "third-party ID suffix"},
		{"<col=ff0000>Inspect</col> the artifact", "<col=00ffff>END</col>", false, PLAIN, "third-party option colours"},
		{"Attack", "<col=ffff00>Guard<col=ff00>  (level-21)", false, PLAIN, "combat-level suffix"},
		{"Teleport", MEDALLION, true, HIGHLIGHT, "bold action highlight"},
		{"<img=0>Teleport", MEDALLION, true, PLAIN, "mod icon"},
	};

	@Test
	public void fittedRuneScapeRowsKeepTheirNaturalRunSpacing()
	{
		Client client = client();
		for (FontChoice font : RS_FONTS)
		{
			Theme theme = theme(font, 12, false);
			for (Object[] entry : ENTRIES)
			{
				RowCase row = new RowCase(theme, (RightClickStylerConfig) entry[3], client,
					(String) entry[0], (String) entry[1], (Boolean) entry[2]);
				int natural = row.naturalWidth();
				List<int[]> expected = segments(row.text(natural));
				assertTrue(font + " " + entry[4] + " has separate runs", expected.size() >= 2);
				for (int shortfall = 1; shortfall <= 4; shortfall++)
				{
					String label = font + " " + entry[4] + " short " + shortfall + "px";
					int width = natural - shortfall;
					Map<Integer, int[]> squeezed = row.text(width);
					assertFalse(label + " is actually fitted", squeezed.equals(row.text(natural)));
					assertGapsPreserved(label, expected, segments(squeezed));
					assertTrue(label + " glyphs keep their width: natural " + extent(row.text(natural))
						+ " fitted " + extent(squeezed), extent(squeezed) >= extent(row.text(natural)) - shortfall - 1);
					assertNothingOutside(label, row.image(width, ""), row.bounds(width));
				}
			}
		}
	}

	@Test
	public void teleportToTargetGapMatchesTheUnfittedRowAtOneToFourPixelsShort()
	{
		// The reported case, with the user's theme (dark, RS Bold, text shadow on, offset 3).
		for (FontChoice font : RS_FONTS)
		{
			for (boolean shadow : new boolean[]{false, true})
			{
				RowCase row = new RowCase(theme(font, 12, shadow), PLAIN, null, "Teleport", MEDALLION, true);
				int natural = row.naturalWidth();
				int gap = optionToTargetGap(row.text(natural));
				assertTrue(font + " natural gap is one space", gap >= 3 && gap <= 7);
				for (int shortfall = 1; shortfall <= 4; shortfall++)
				{
					assertEquals(font + " shadow=" + shadow + " short " + shortfall + "px",
						gap, optionToTargetGap(row.text(natural - shortfall)), 1);
				}
			}
		}
	}

	@Test
	public void fittedFormattedHeadersKeepTheirNaturalRunSpacing()
	{
		String[] headers = {
			"<col=ff9040>Drakan's medallion<col=dfab23> (ID: 22400)",
			"<col=ffff00>Guard<col=ff00>  (level-21)",
			"Choose <col=ff9040>Option",
		};
		for (FontChoice font : RS_FONTS)
		{
			Theme theme = theme(font, 12, false);
			for (String header : headers)
			{
				HeaderCase head = new HeaderCase(theme, header);
				int natural = head.naturalWidth();
				List<int[]> expected = segments(head.text(natural));
				assertTrue(font + " " + header + " has separate runs", expected.size() >= 2);
				for (int shortfall = 1; shortfall <= 4; shortfall++)
				{
					String label = font + " header " + header + " short " + shortfall + "px";
					Map<Integer, int[]> squeezed = head.text(natural - shortfall);
					assertFalse(label + " is actually fitted", squeezed.equals(head.text(natural)));
					assertGapsPreserved(label, expected, segments(squeezed));
					assertNothingOutside(label, head.image(natural - shortfall), head.bounds(natural - shortfall));
				}
			}
		}
	}

	@Test
	public void rowsThatFitAreDrawnExactlyAsPlainText()
	{
		for (FontChoice font : FontChoice.values())
		{
			for (int size : sizes(font))
			{
				for (boolean sub : new boolean[]{false, true})
				{
					for (String option : new String[]{"Teleport", "Swap shift-click"})
					{
						Theme theme = theme(font, size, false);
						RowCase row = new RowCase(theme, PLAIN, null, option, MEDALLION, sub);
						int natural = row.naturalWidth();
						for (int slack : new int[]{0, 1, 5, 40})
						{
							String label = font + "/" + size + " sub=" + sub + " " + option + " slack " + slack;
							assertMasksEqual(label, oracle(theme, option, "Drakan's medallion", sub, natural + slack),
								row.mask(natural + slack));
						}
					}
				}
			}
		}
	}

	@Test
	public void fittedSansAndSerifRowsAreDrawnExactlyAsBefore()
	{
		for (FontChoice font : new FontChoice[]{FontChoice.SANS, FontChoice.SERIF})
		{
			for (int size : sizes(font))
			{
				for (boolean sub : new boolean[]{false, true})
				{
					for (String option : new String[]{"Teleport", "Swap shift-click"})
					{
						Theme theme = theme(font, size, false);
						RowCase row = new RowCase(theme, PLAIN, null, option, MEDALLION, sub);
						int natural = row.naturalWidth();
						for (int shortfall : new int[]{1, 2, 3, 4, 20})
						{
							String label = font + "/" + size + " sub=" + sub + " " + option + " short " + shortfall;
							assertMasksEqual(label,
								oracle(theme, option, "Drakan's medallion", sub, natural - shortfall),
								row.mask(natural - shortfall));
						}
					}
				}
			}
		}
	}

	/**
	 * Every font, size, offset, arrow, shadow, entry kind and a range of widths down to extreme
	 * fits (6,048 cases): nothing leaves the native rectangle, and fitted text stays inside its text
	 * box. Below half scale a fitted glyph's raster may reach 1px past its scaled advance.
	 */
	@Test
	public void fittedTextStaysInsideItsTextBoxAndTheNativeRectangle()
	{
		String[][] entries = {
			{"Teleport", MEDALLION},
			{"Swap shift-click", MEDALLION},
			{"Pickpocket", "<col=ffff00>Guard<col=ff00>  (level-21)"},
			{"<col=ff0000>Inspect</col> the enchanted artifact", "<col=00ff00>END</col>"},
			{"Use", "<col=ff9040>Dragon scimitar<col=dfab23> (ID: 4587)"},
			{"", "<col=ff9040>Target only long name here</col>"},
			{"Option only quite long text here", ""},
		};
		int cases = 0;
		for (FontChoice font : FontChoice.values())
		{
			for (int size : font.isScalable() ? new int[]{11, 12, 13} : new int[]{12})
			{
				for (int offset : new int[]{0, 3, 8, 20})
				{
					RightClickStylerConfig config = new RightClickStylerConfig()
					{
						@Override
						public int textOffsetX()
						{
							return offset;
						}
					};
					for (boolean sub : new boolean[]{false, true})
					{
						for (boolean shadow : new boolean[]{false, true})
						{
							Theme theme = theme(font, size, shadow);
							FontMetrics metrics = metrics(theme);
							for (String[] entry : entries)
							{
								for (int width : new int[]{60, 90, 120, 150, 180, 210})
								{
									cases++;
									String label = font + "/" + size + " offset=" + offset + " sub=" + sub
										+ " shadow=" + shadow + " width=" + width + " " + entry[0] + " | " + entry[1];
									assertContained(label, theme, config, metrics, entry, sub, shadow, offset, width);
								}
							}
						}
					}
				}
			}
		}
		assertEquals(6048, cases);
	}

	// ---- assertions

	private static void assertContained(String label, Theme theme, RightClickStylerConfig config, FontMetrics metrics,
		String[] entry, boolean sub, boolean shadow, int offset, int width)
	{
		Rectangle bounds = new Rectangle(20, 20, width, MenuRenderer.HEADER_HEIGHT + MenuRenderer.ROW_HEIGHT);
		String header = (entry[0] + " " + entry[1]).trim();
		BufferedImage drawn = render(theme, config, null, row(entry[0], entry[1], sub), bounds, header);
		BufferedImage noRow = render(theme, config, null, row("", "", sub), bounds, header);
		BufferedImage noHeader = render(theme, config, null, row(entry[0], entry[1], sub), bounds, "");
		assertNothingOutside(label, drawn, bounds);

		int rowEnd = width - Math.max(sub ? 16 : 4, offset) - 1;
		int headerEnd = width - Math.max(4, offset) - 1;
		int lastRow = -1;
		int lastHeader = -1;
		for (int y = bounds.y; y < bounds.y + bounds.height; y++)
		{
			boolean inRow = y >= bounds.y + MenuRenderer.HEADER_HEIGHT;
			for (int x = bounds.x; x < bounds.x + bounds.width; x++)
			{
				if (inRow && drawn.getRGB(x, y) != noRow.getRGB(x, y))
				{
					lastRow = Math.max(lastRow, x - bounds.x);
				}
				if (!inRow && drawn.getRGB(x, y) != noHeader.getRGB(x, y))
				{
					lastHeader = Math.max(lastHeader, x - bounds.x);
				}
			}
		}
		int allowance = shadow ? 1 : 0;
		String content = collapse(theme, Text.removeTags(entry[0] + " " + entry[1]).trim());
		int contentWidth = metrics.stringWidth(content);
		int rowTolerance = (rowEnd - offset) * 2 >= contentWidth ? 0 : 1;
		int headerTolerance = (headerEnd - offset) * 2 >= contentWidth ? 0 : 1;
		assertTrue(label + " row text ends " + lastRow + ", box ends " + rowEnd,
			lastRow <= rowEnd - 1 + allowance + rowTolerance);
		assertTrue(label + " header text ends " + lastHeader + ", box ends " + headerEnd,
			lastHeader <= headerEnd - 1 + allowance + headerTolerance);
	}

	private static void assertGapsPreserved(String label, List<int[]> expected, List<int[]> actual)
	{
		assertEquals(label + " run count " + describe(expected) + " vs " + describe(actual),
			expected.size(), actual.size());
		for (int i = 0; i + 1 < expected.size(); i++)
		{
			int natural = expected.get(i + 1)[0] - expected.get(i)[1] - 1;
			int fitted = actual.get(i + 1)[0] - actual.get(i)[1] - 1;
			assertEquals(label + " gap " + i + " natural " + describe(expected) + " fitted " + describe(actual),
				natural, fitted, 1);
		}
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

	private static void assertMasksEqual(String label, boolean[][] expected, boolean[][] actual)
	{
		boolean any = false;
		for (int y = 0; y < expected.length; y++)
		{
			for (int x = 0; x < expected[y].length; x++)
			{
				any |= expected[y][x];
				if (expected[y][x] != actual[y][x])
				{
					fail(label + " differs from plain text drawing at column " + x + " row " + y);
				}
			}
		}
		assertTrue(label + " drew text", any);
	}

	// ---- measurement

	/** One row in a menu whose width varies; text pixels are those the row's option and target add. */
	private static final class RowCase
	{
		private final Theme theme;
		private final RightClickStylerConfig config;
		private final Client client;
		private final String option;
		private final String target;
		private final boolean sub;

		private RowCase(Theme theme, RightClickStylerConfig config, Client client, String option, String target,
			boolean sub)
		{
			this.theme = theme;
			this.config = config;
			this.client = client;
			this.option = option;
			this.target = target;
			this.sub = sub;
		}

		private Rectangle bounds(int width)
		{
			return new Rectangle(20, 20, width, MenuRenderer.HEADER_HEIGHT + MenuRenderer.ROW_HEIGHT);
		}

		private BufferedImage image(int width, String header)
		{
			return render(theme, config, client, row(option, target, sub), bounds(width), header);
		}

		/** x -> {y, rgb} pairs of text pixels, keyed by x then y, relative to the menu's left edge. */
		private Map<Integer, int[]> text(int width)
		{
			Rectangle bounds = bounds(width);
			return difference(image(width, ""), render(theme, config, client, row("", "", sub), bounds, ""),
				bounds, bounds.y + MenuRenderer.HEADER_HEIGHT);
		}

		private boolean[][] mask(int width)
		{
			return toMask(text(width), width);
		}

		/** The narrowest menu whose row draws exactly as it does with ample room. */
		private int naturalWidth()
		{
			Map<Integer, int[]> roomy = text(600);
			int low = 20;
			int high = 600;
			while (low < high)
			{
				int middle = (low + high) >>> 1;
				if (text(middle).equals(roomy))
				{
					high = middle;
				}
				else
				{
					low = middle + 1;
				}
			}
			return low;
		}
	}

	private static final class HeaderCase
	{
		private final Theme theme;
		private final String header;

		private HeaderCase(Theme theme, String header)
		{
			this.theme = theme;
			this.header = header;
		}

		private Rectangle bounds(int width)
		{
			return new Rectangle(20, 20, width, MenuRenderer.HEADER_HEIGHT + MenuRenderer.ROW_HEIGHT);
		}

		private BufferedImage image(int width)
		{
			return render(theme, PLAIN, null, row("", "", false), bounds(width), header);
		}

		private Map<Integer, int[]> text(int width)
		{
			Rectangle bounds = bounds(width);
			return difference(image(width), render(theme, PLAIN, null, row("", "", false), bounds, ""),
				bounds, bounds.y);
		}

		private int naturalWidth()
		{
			Map<Integer, int[]> roomy = text(600);
			int low = 20;
			int high = 600;
			while (low < high)
			{
				int middle = (low + high) >>> 1;
				if (text(middle).equals(roomy))
				{
					high = middle;
				}
				else
				{
					low = middle + 1;
				}
			}
			return low;
		}
	}

	/** Differing pixels in one 15/19px band, keyed by x * 100 + y, valued by colour. */
	private static Map<Integer, int[]> difference(BufferedImage a, BufferedImage b, Rectangle bounds, int top)
	{
		Map<Integer, int[]> pixels = new TreeMap<>();
		int height = top == bounds.y ? MenuRenderer.HEADER_HEIGHT : MenuRenderer.ROW_HEIGHT;
		for (int y = top; y < top + height; y++)
		{
			for (int x = bounds.x; x < bounds.x + bounds.width; x++)
			{
				int rgb = a.getRGB(x, y);
				if (rgb != b.getRGB(x, y))
				{
					pixels.put((x - bounds.x) * 100 + (y - top), new int[]{rgb});
				}
			}
		}
		return new TreeMap<Integer, int[]>(pixels)
		{
			@Override
			public boolean equals(Object other)
			{
				if (!(other instanceof Map) || ((Map<?, ?>) other).size() != size())
				{
					return false;
				}
				for (Map.Entry<Integer, int[]> entry : entrySet())
				{
					Object value = ((Map<?, ?>) other).get(entry.getKey());
					if (!(value instanceof int[]) || ((int[]) value)[0] != entry.getValue()[0])
					{
						return false;
					}
				}
				return true;
			}

			@Override
			public int hashCode()
			{
				return keySet().hashCode();
			}
		};
	}

	/** Runs of one colour, ordered by their first column: {first, last, rgb}. */
	private static List<int[]> segments(Map<Integer, int[]> pixels)
	{
		Map<Integer, int[]> byColour = new TreeMap<>();
		for (Map.Entry<Integer, int[]> entry : pixels.entrySet())
		{
			int x = entry.getKey() / 100;
			int rgb = entry.getValue()[0] & 0xFFFFFF;
			int[] range = byColour.computeIfAbsent(rgb, colour -> new int[]{x, x, colour});
			range[0] = Math.min(range[0], x);
			range[1] = Math.max(range[1], x);
		}
		List<int[]> segments = new ArrayList<>(byColour.values());
		segments.sort((a, b) -> Integer.compare(a[0], b[0]));
		return segments;
	}

	private static int optionToTargetGap(Map<Integer, int[]> pixels)
	{
		int optionEnd = -1;
		int targetStart = Integer.MAX_VALUE;
		for (Map.Entry<Integer, int[]> entry : pixels.entrySet())
		{
			int x = entry.getKey() / 100;
			if ((entry.getValue()[0] & 0xFFFFFF) == 0xFF9040)
			{
				targetStart = Math.min(targetStart, x);
			}
		}
		for (Map.Entry<Integer, int[]> entry : pixels.entrySet())
		{
			int x = entry.getKey() / 100;
			if (x < targetStart)
			{
				optionEnd = Math.max(optionEnd, x);
			}
		}
		return targetStart - optionEnd - 1;
	}

	private static int extent(Map<Integer, int[]> pixels)
	{
		int first = Integer.MAX_VALUE;
		int last = -1;
		for (int key : pixels.keySet())
		{
			first = Math.min(first, key / 100);
			last = Math.max(last, key / 100);
		}
		return last - first + 1;
	}

	private static boolean[][] toMask(Map<Integer, int[]> pixels, int width)
	{
		boolean[][] mask = new boolean[MenuRenderer.ROW_HEIGHT][width];
		for (int key : pixels.keySet())
		{
			mask[key % 100][key / 100] = true;
		}
		return mask;
	}

	private static String describe(List<int[]> segments)
	{
		StringBuilder text = new StringBuilder();
		for (int[] segment : segments)
		{
			text.append(String.format("[%d..%d #%06X]", segment[0], segment[1], segment[2]));
		}
		return text.toString();
	}

	/**
	 * The renderer's drawing without fitting help: the option and the target drawn as plain strings,
	 * one space apart, at the row's text start; scaled into the text box when they overflow it.
	 */
	private static boolean[][] oracle(Theme theme, String option, String target, boolean sub, int width)
	{
		int offset = Limits.textOffsetX(PLAIN);
		int maxX = width - Math.max(sub ? 16 : 4, offset) - 1;
		BufferedImage image = new BufferedImage(width, MenuRenderer.ROW_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			Font font = font(theme);
			graphics.setFont(font);
			FontMetrics metrics = graphics.getFontMetrics(font);
			int baseline = (MenuRenderer.ROW_HEIGHT - (metrics.getAscent() + metrics.getDescent())) / 2
				+ metrics.getAscent();
			int targetX = metrics.stringWidth(option) + metrics.charWidth(' ');
			int measured = targetX + metrics.stringWidth(target);
			int available = maxX - offset;
			graphics.setColor(Color.WHITE);
			if (measured > available)
			{
				graphics.translate(offset, 0);
				graphics.scale((double) available / measured, 1);
				graphics.drawString(option, 0, baseline);
				graphics.drawString(target, targetX, baseline);
			}
			else
			{
				graphics.drawString(option, offset, baseline);
				graphics.drawString(target, offset + targetX, baseline);
			}
		}
		finally
		{
			graphics.dispose();
		}
		boolean[][] mask = new boolean[MenuRenderer.ROW_HEIGHT][width];
		for (int y = 0; y < MenuRenderer.ROW_HEIGHT; y++)
		{
			for (int x = 0; x < width; x++)
			{
				mask[y][x] = (image.getRGB(x, y) >>> 24) != 0;
			}
		}
		return mask;
	}

	// ---- fixtures

	private static Theme theme(FontChoice font, int size, boolean textShadow)
	{
		return BuiltInThemes.find("dark").withFont(font).withFontSize(size).withTextShadow(textShadow)
			.withShowShadow(false).withShowBorder(false).withRadius(0).withOpacity(100).withHoverOpacity(100);
	}

	private static int[] sizes(FontChoice font)
	{
		return font.isScalable() ? new int[]{Limits.MIN_FONT_SIZE, 12, Limits.MAX_FONT_SIZE} : new int[]{12};
	}

	private static Font font(Theme theme)
	{
		switch (theme.font)
		{
			case RS_REGULAR:
				return FontManager.getRunescapeFont();
			case RS_SMALL:
				return FontManager.getRunescapeSmallFont();
			case RS_BOLD:
				return FontManager.getRunescapeBoldFont();
			default:
				return new Font(theme.font.family, Font.PLAIN, theme.fontSize);
		}
	}

	private static FontMetrics metrics(Theme theme)
	{
		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = scratch.createGraphics();
		try
		{
			return graphics.getFontMetrics(font(theme));
		}
		finally
		{
			graphics.dispose();
		}
	}

	private static String collapse(Theme theme, String text)
	{
		return theme.font.isScalable() ? text.replaceAll("\\s+", " ") : text;
	}

	private static List<MenuRenderer.Row> row(String option, String target, boolean sub)
	{
		MenuRenderer.Row row = new MenuRenderer.Row(null, null, MenuRenderer.TargetKind.OTHER);
		row.set(option, target, MenuRenderer.TargetKind.OTHER, sub, false);
		return Collections.singletonList(row);
	}

	private static BufferedImage render(Theme theme, RightClickStylerConfig config, Client client,
		List<MenuRenderer.Row> rows, Rectangle bounds, String header)
	{
		BufferedImage image = new BufferedImage(bounds.x + bounds.width + 40, bounds.y + bounds.height + 10,
			BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			new MenuRenderer().render(graphics, client, theme, config, rows, bounds, -1, 0, header);
		}
		finally
		{
			graphics.dispose();
		}
		return image;
	}

	/** A client whose only mod icon is a solid 10x10 square in a colour no text uses. */
	private static Client client()
	{
		byte[] pixels = new byte[100];
		Arrays.fill(pixels, (byte) 1);
		IndexedSprite sprite = (IndexedSprite) Proxy.newProxyInstance(IndexedSprite.class.getClassLoader(),
			new Class<?>[]{IndexedSprite.class}, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getWidth":
					case "getHeight":
						return 10;
					case "getPixels":
						return pixels;
					case "getPalette":
						return new int[]{0, ICON_COLOUR};
					default:
						return objectMethod(proxy, method.getName(), args, method.getReturnType());
				}
			});
		IndexedSprite[] icons = {sprite};
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) -> "getModIcons".equals(method.getName())
				? icons : objectMethod(proxy, method.getName(), args, method.getReturnType()));
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
