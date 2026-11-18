package com.rightclickstyler;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assume.assumeTrue;

/**
 * Timing only, skipped unless -Drightclickstyler.benchmark=true. Isolates which theme
 * properties make the themed menu render expensive, one property at a time, on the same
 * 72-row root the prototype draws (about 38 visible rows) into the same ARGB image type.
 */
public class ThemeRenderBenchmarkTest
{
	private static final int RUNS = 400;

	@Test
	public void themePropertiesThatDriveRenderCost()
	{
		assumeTrue(Boolean.getBoolean("rightclickstyler.benchmark"));
		Theme classic = find("classic");
		Theme midnight = find("midnight");
		Object[][] variants = {
			{"classic (radius 0, opacity 100, TEXT hover)", classic},
			{"classic + radius 8", with(classic, 8, classic.opacity, classic.hoverStyle)},
			{"classic + opacity 92", with(classic, 0, 92, classic.hoverStyle)},
			{"classic + BAR hover", with(classic, 0, classic.opacity, HoverStyle.BAR)},
			{"midnight (radius 8, opacity 92, BAR hover)", midnight},
			{"midnight with radius 0", with(midnight, 0, midnight.opacity, midnight.hoverStyle)},
			{"midnight with radius 0, opacity 100", with(midnight, 0, 100, midnight.hoverStyle)},
		};
		RightClickStylerConfig plain = new RightClickStylerConfig() { };
		RightClickStylerConfig live = new RightClickStylerConfig()
		{
			// The live session's settings: bold highlight rules and a 3px text offset.
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
				return "Attack=#FF5C5C\nWalk here=#7D8590\nExamine=#7D8590";
			}

			@Override
			public int textOffsetX()
			{
				return 3;
			}
		};
		StringBuilder report = new StringBuilder();
		for (boolean liveText : new boolean[]{false, true})
		{
			List<MenuRenderer.Row> rows = rows(liveText);
			for (Object[] variant : variants)
			{
				Theme theme = (Theme) variant[1];
				for (RightClickStylerConfig config : new RightClickStylerConfig[]{plain, live})
				{
					long[] nanos = time(theme, config, rows);
					report.append(String.format("BENCHTHEME text=%-6s config=%-5s %-46s p50=%5dus p90=%5dus%n",
						liveText ? "tagged" : "plain", config == live ? "live" : "plain", variant[0],
						nanos[RUNS / 2] / 1000, nanos[RUNS * 9 / 10] / 1000));
				}
			}
		}
		System.out.print(report);
	}

	private static long[] time(Theme theme, RightClickStylerConfig config, List<MenuRenderer.Row> rows)
	{
		MenuRenderer renderer = new MenuRenderer();
		Rectangle bounds = new Rectangle(4, 0, 228, 22 + rows.size() * MenuRenderer.ROW_HEIGHT);
		BufferedImage image = new BufferedImage(480, 592, BufferedImage.TYPE_INT_ARGB);
		long[] nanos = new long[RUNS];
		for (int i = -200; i < RUNS; i++)
		{
			long start = System.nanoTime();
			Graphics2D g = image.createGraphics();
			try
			{
				g.setComposite(AlphaComposite.Clear);
				g.fillRect(0, 0, image.getWidth(), image.getHeight());
				g.setComposite(AlphaComposite.SrcOver);
				renderer.render(g, null, theme, config, rows, bounds, 10, 90, "Choose Option", 0);
			}
			finally
			{
				g.dispose();
			}
			if (i >= 0)
			{
				nanos[i] = System.nanoTime() - start;
			}
		}
		Arrays.sort(nanos);
		return nanos;
	}

	/** 72 rows; "tagged" adds the colour tags and combat levels real NPC and player rows carry. */
	private static List<MenuRenderer.Row> rows(boolean tagged)
	{
		List<MenuRenderer.Row> rows = new ArrayList<>();
		for (int i = 0; i < 72; i++)
		{
			String target = tagged
				? "<col=ffff00>Grand Exchange Clerk<col=ff00>  (level-" + (i % 120) + ")"
				: "Grand Exchange Clerk";
			rows.add(new MenuRenderer.Row(i % 7 == 0 ? "Attack" : "Talk-to", target,
				i % 2 == 0 ? MenuRenderer.TargetKind.NPC : MenuRenderer.TargetKind.PLAYER));
		}
		return rows;
	}

	private static Theme find(String id)
	{
		return BuiltInThemes.ALL.stream().filter(t -> t.id.equals(id)).findFirst().get();
	}

	private static Theme with(Theme t, int radius, int opacity, HoverStyle hover)
	{
		return new Theme(t.id + "-variant", t.name, false, t.bg, t.border, t.header, t.headerText, t.text,
			t.hover, t.accent, radius, opacity, hover, t.showBorder, t.showShadow, t.showHeader, t.font,
			t.fontSize, t.textShadow);
	}
}
