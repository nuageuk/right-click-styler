package experiments;

import java.awt.Rectangle;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Rasterizer;

/** Investigation-only differential probe for overlapping submenu rectangles. */
public final class SubmenuMutationProbe
{
	private SubmenuMutationProbe()
	{
	}

	public static Outcome probe(Client client, Menu candidate)
	{
		Rasterizer rasterizer = client.getRasterizer();
		BufferProvider provider = client.getBufferProvider();
		if (rasterizer == null || provider == null
			|| rasterizer.getPixels() == null || rasterizer.getPixels() != provider.getPixels()
			|| rasterizer.getWidth() != provider.getWidth()
			|| rasterizer.getHeight() != provider.getHeight())
		{
			return Outcome.INDETERMINATE;
		}
		MenuEntry[] entries = candidate.getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return Outcome.INDETERMINATE;
		}
		String[] originals = new String[entries.length];
		for (int i = 0; i < entries.length; i++)
		{
			originals[i] = entries[i] == null ? null : entries[i].getOption();
		}
		Rectangle bounds = new Rectangle(candidate.getMenuX(), candidate.getMenuY(),
			candidate.getMenuWidth(), candidate.getMenuHeight());
		int[] pixels = rasterizer.getPixels();
		int[] before = pixels.clone();
		try
		{
			client.drawOriginalMenu(255);
			int[] baseline = pixels.clone();
			System.arraycopy(before, 0, pixels, 0, pixels.length);
			client.drawOriginalMenu(255);
			if (difference(baseline, pixels, rasterizer.getWidth(), rasterizer.getHeight(), bounds) > 0)
			{
				return Outcome.INDETERMINATE;
			}
			System.arraycopy(before, 0, pixels, 0, pixels.length);
			for (int i = 0; i < entries.length; i++)
			{
				if (entries[i] != null)
				{
					entries[i].setOption("W".equals(originals[i]) ? "M" : "W");
				}
			}
			client.drawOriginalMenu(255);
			return difference(baseline, pixels, rasterizer.getWidth(), rasterizer.getHeight(), bounds) > 0
				? Outcome.VISIBLE : Outcome.NOT_VISIBLE;
		}
		finally
		{
			for (int i = 0; i < entries.length; i++)
			{
				if (entries[i] != null)
				{
					entries[i].setOption(originals[i]);
				}
			}
			System.arraycopy(before, 0, pixels, 0, pixels.length);
		}
	}

	static int difference(int[] a, int[] b, int width, int height, Rectangle region)
	{
		Rectangle r = region.intersection(new Rectangle(0, 0, width, height));
		int count = 0;
		for (int y = r.y; y < r.y + r.height; y++)
		{
			for (int x = r.x; x < r.x + r.width; x++)
			{
				int index = y * width + x;
				if (a[index] != b[index])
				{
					count++;
				}
			}
		}
		return count;
	}

	public enum Outcome
	{
		VISIBLE, NOT_VISIBLE, INDETERMINATE
	}
}
