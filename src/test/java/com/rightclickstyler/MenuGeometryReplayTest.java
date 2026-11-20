package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.IndexedSprite;
import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Replays live RCS_MENU_GEOMETRY lines. No fabricated native dimensions are used. */
public class MenuGeometryReplayTest
{
	private static final Gson GSON = new Gson();
	private static final String MARKER = "RCS_MENU_GEOMETRY ";

	@Test
	public void replayAllCapturedDimensionsAndDecisions() throws Exception
	{
		for (MenuRenderer.GeometryCapture capture : captures())
		{
			MenuRenderer.GeometryCapture replay = replay(capture);
			assertEquals(capture.fontFamily, replay.fontFamily);
			assertEquals(capture.fontStyle, replay.fontStyle);
			assertEquals(capture.fontSize, replay.fontSize);
			if (!capture.header.rawTruncated)
			{
				assertHeader(capture, replay.header);
			}
			assertEquals(capture.rows.size(), replay.rows.size());
			for (int i = 0; i < capture.rows.size(); i++)
			{
				if (!capture.rows.get(i).rawTruncated)
				{
					assertRow(capture.rows.get(i), replay.rows.get(i));
				}
			}
		}
	}

	@Test
	public void plainNativeBoldRowsNeedNoFitting() throws Exception
	{
		int checked = 0;
		for (MenuRenderer.GeometryCapture capture : captures())
		{
			if (!"RS_BOLD".equals(capture.themeFont) || capture.menuWidth >= capture.canvasWidth)
			{
				continue;
			}
			MenuRenderer.GeometryCapture replay = replay(capture);
			for (MenuRenderer.GeometryCapture.Entry entry : replay.rows)
			{
				if ("plain".equals(entry.kind) && !entry.rawTruncated && entry.iconWidth == 0
					&& entry.rawOption != null && !entry.rawOption.isEmpty())
				{
					checked++;
					assertFalse("native-sized Bold row compressed: " + entry.rawOption, entry.compress);
					assertFalse("native-sized Bold row ellipsized: " + entry.rawOption, entry.ellipsize);
				}
			}
		}
		Assume.assumeTrue("Capture an ordinary RS Bold menu first", checked > 0);
	}

	@Test
	public void formattedOptionAndTargetReplay() throws Exception
	{
		assertCase("optionTarget", false);
	}

	@Test
	public void chooseOptionHeaderReplay() throws Exception
	{
		int checked = 0;
		for (MenuRenderer.GeometryCapture capture : captures())
		{
			if ("Choose Option".equals(capture.header.rawOption) && !capture.header.rawTruncated)
			{
				assertHeader(capture, replay(capture).header);
				checked++;
			}
		}
		Assume.assumeTrue("Capture a Choose Option header first", checked > 0);
	}

	@Test
	public void submenuRowsReplay() throws Exception
	{
		assertCase("submenu", false);
	}

	@Test
	public void imageTagsReplayWhenCaptured() throws Exception
	{
		assertCase(null, true);
	}

	private static void assertCase(String kind, boolean image) throws Exception
	{
		int checked = 0;
		for (MenuRenderer.GeometryCapture capture : captures())
		{
			MenuRenderer.GeometryCapture replay = replay(capture);
			for (int i = 0; i < capture.rows.size(); i++)
			{
				MenuRenderer.GeometryCapture.Entry entry = capture.rows.get(i);
				if (!entry.rawTruncated && (kind == null || kind.equals(entry.kind))
					&& (!image || entry.nativeCombined.contains("<img=")))
				{
					assertRow(entry, replay.rows.get(i));
					checked++;
				}
			}
		}
		Assume.assumeTrue("No captured " + (image ? "image" : kind) + " row", checked > 0);
	}

	private static void assertEntry(MenuRenderer.GeometryCapture.Entry expected,
		MenuRenderer.GeometryCapture.Entry actual)
	{
		assertEquals(expected.nativeCombined, actual.nativeCombined);
		assertEquals(expected.fontMetricsPlainWidth, actual.fontMetricsPlainWidth);
		assertEquals(expected.leftPadding, actual.leftPadding);
		assertEquals(expected.rightPadding, actual.rightPadding);
		assertEquals(expected.shadowAllowance, actual.shadowAllowance);
		assertEquals(expected.arrowAllowance, actual.arrowAllowance);
		assertEquals(expected.availableWidth, actual.availableWidth);
		assertEquals(expected.contentWidth, actual.contentWidth);
		assertEquals(expected.optionWidth, actual.optionWidth);
		assertEquals(expected.targetWidth, actual.targetWidth);
		assertEquals(expected.spacingWidth, actual.spacingWidth);
		assertEquals(expected.iconWidth, actual.iconWidth);
		assertEquals(expected.optionIconWidth, actual.optionIconWidth);
		assertEquals(expected.targetIconWidth, actual.targetIconWidth);
		assertEquals(expected.optionTextWidth, actual.optionTextWidth);
		assertEquals(expected.targetTextWidth, actual.targetTextWidth);
		assertEquals(expected.legacyEllipsizeOrDrop, actual.legacyEllipsizeOrDrop);
		assertEquals(expected.compress, actual.compress);
		assertEquals(expected.ellipsize, actual.ellipsize);
		assertEquals(expected.drop, actual.drop);
	}

	private static void assertRow(MenuRenderer.GeometryCapture.Entry original,
		MenuRenderer.GeometryCapture.Entry replayed)
	{
		if ("submenu".equals(original.kind) && !original.opensLeft
			&& original.rightPadding == 17)
		{
			assertEquals(original.nativeCombined, replayed.nativeCombined);
			assertEquals(original.contentWidth, replayed.contentWidth);
			assertEquals(original.optionWidth, replayed.optionWidth);
			assertEquals(original.targetWidth, replayed.targetWidth);
			assertEquals(original.spacingWidth, replayed.spacingWidth);
			assertEquals(original.iconWidth, replayed.iconWidth);
			assertEquals(original.leftPadding, replayed.leftPadding);
			assertEquals(16, replayed.rightPadding);
			assertEquals(16, replayed.arrowAllowance);
			assertEquals(original.availableWidth + 1, replayed.availableWidth);
			if (original.contentWidth <= replayed.availableWidth)
			{
				assertFalse("right arrow reserve no longer compresses a fitting row", replayed.compress);
				assertFalse(replayed.ellipsize);
				assertFalse(replayed.drop);
			}
		}
		else
		{
			assertEntry(original, replayed);
		}
	}

	private static void assertHeader(MenuRenderer.GeometryCapture capture,
		MenuRenderer.GeometryCapture.Entry replayed)
	{
		MenuRenderer.GeometryCapture.Entry original = capture.header;
		if (original.ellipsize && original.contentWidth <= capture.menuWidth - 8)
		{
			assertEquals(original.contentWidth, replayed.contentWidth);
			assertFalse("header fits at native text inset", replayed.ellipsize);
			assertFalse("header remains visible", replayed.drop);
			assertTrue(replayed.availableWidth >= replayed.contentWidth);
		}
		else
		{
			assertEntry(original, replayed);
		}
	}

	private static List<MenuRenderer.GeometryCapture> captures() throws Exception
	{
		String path = System.getProperty("rightclickstyler.menuGeometryReplay", "");
		Assume.assumeTrue("Pass -PmenuGeometryReplay=<captured-jsonl>", !path.isEmpty());
		List<MenuRenderer.GeometryCapture> result = new ArrayList<>();
		for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8))
		{
			int marker = line.indexOf(MARKER);
			if (marker >= 0)
			{
				MenuRenderer.GeometryCapture capture = GSON.fromJson(
					line.substring(marker + MARKER.length()), MenuRenderer.GeometryCapture.class);
				assertEquals(1, capture.schema);
				result.add(capture);
			}
		}
		assertTrue("Capture file has no RCS_MENU_GEOMETRY lines", !result.isEmpty());
		return result;
	}

	private static MenuRenderer.GeometryCapture replay(MenuRenderer.GeometryCapture sample)
	{
		Theme theme = BuiltInThemes.find(BuiltInThemes.DEFAULT_ID)
			.withFont(FontChoice.valueOf(sample.themeFont)).withFontSize(sample.configuredSize)
			.withOpacity(sample.opacity).withHoverOpacity(sample.hoverOpacity)
			.withRadius(sample.radius).withShowHeader(sample.showHeader)
			.withHoverStyle(HoverStyle.valueOf(sample.hoverStyle));
		RightClickStylerConfig config = new RightClickStylerConfig()
		{
			@Override public int textOffsetX() { return sample.textOffsetX; }
			@Override public boolean highlightsEnabled() { return sample.highlightsEnabled; }
			@Override public boolean highlightsBold() { return sample.highlightsBold; }
			@Override public String highlightRules() { return sample.highlightRules; }
			@Override public CombatLevelMode combatLevelMode()
			{
				return CombatLevelMode.valueOf(sample.combatLevelMode);
			}
		};
		List<MenuRenderer.Row> rows = new ArrayList<>();
		for (MenuRenderer.GeometryCapture.Entry entry : sample.rows)
		{
			MenuRenderer.Row row = new MenuRenderer.Row(entry.rawOption, entry.rawTarget,
				MenuRenderer.TargetKind.OTHER);
			row.set(entry.rawOption, entry.rawTarget, MenuRenderer.TargetKind.OTHER,
				"submenu".equals(entry.kind), entry.opensLeft);
			rows.add(row);
		}
		BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			return new MenuRenderer().inspectGeometry(graphics, iconClient(sample.iconSizes), theme,
				config, rows, new Rectangle(sample.menuX, sample.menuY,
					sample.menuWidth, sample.menuHeight), sample.hoverIndex,
				sample.header.rawOption, sample.path, sample.canvasWidth, sample.canvasHeight);
		}
		finally
		{
			graphics.dispose();
		}
	}

	private static Client iconClient(Map<Integer, int[]> dimensions)
	{
		int max = dimensions.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
		IndexedSprite[] icons = new IndexedSprite[max + 1];
		for (Map.Entry<Integer, int[]> entry : dimensions.entrySet())
		{
			int width = entry.getValue()[0];
			int height = entry.getValue()[1];
			icons[entry.getKey()] = (IndexedSprite) Proxy.newProxyInstance(
				IndexedSprite.class.getClassLoader(), new Class<?>[]{IndexedSprite.class},
				(proxy, method, args) -> {
					switch (method.getName())
					{
						case "getWidth": return width;
						case "getHeight": return height;
						case "getPixels": return new byte[Math.max(0, width * height)];
						case "getPalette": return new int[]{0, 0xFFFFFF};
						default: return null;
					}
				});
		}
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(),
			new Class<?>[]{Client.class}, (proxy, method, args) ->
				"getModIcons".equals(method.getName()) ? icons : null);
	}
}
