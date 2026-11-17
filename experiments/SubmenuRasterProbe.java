package experiments;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Rasterizer;

/**
 * Investigation-only probe. Call from BeforeMenuRender on the client thread.
 * It never consumes the event or changes menu entries.
 */
public final class SubmenuRasterProbe
{
	private static final int SENTINEL = 0x13579BDF;
	private static final int WITNESS_MARGIN = 2;

	private SubmenuRasterProbe()
	{
	}

	public static Result probe(Client client)
	{
		Rasterizer rasterizer = client.getRasterizer();
		BufferProvider provider = client.getBufferProvider();
		if (rasterizer == null || provider == null)
		{
			return new Result("missing buffer", 0, new int[0], new int[0]);
		}
		int[] rasterPixels = rasterizer.getPixels();
		int[] providerPixels = provider.getPixels();
		int width = rasterizer.getWidth();
		int height = rasterizer.getHeight();
		if (rasterPixels == null || rasterPixels != providerPixels
			|| width != provider.getWidth() || height != provider.getHeight()
			|| width <= 0 || height <= 0 || (long) width * height > rasterPixels.length)
		{
			return new Result("raster/provider mismatch", 0, new int[0], new int[0]);
		}

		Menu root = client.getMenu();
		List<Rectangle> candidates = new ArrayList<>();
		List<Integer> parents = new ArrayList<>();
		if (!collect(root, candidates, parents, new IdentityHashMap<>(), -1, 0))
		{
			return new Result("unsupported menu graph", 0, new int[0], new int[0]);
		}
		int[] parentIndexes = new int[parents.size()];
		for (int i = 0; i < parents.size(); i++)
		{
			parentIndexes[i] = parents.get(i);
		}
		return probePixels(rasterPixels, width, height, bounds(root), candidates,
			parentIndexes, () -> client.drawOriginalMenu(255));
	}

	/** The same algorithm with an injectable draw action for isolated tests. */
	static Result probePixels(int[] pixels, int width, int height, Rectangle root,
		List<Rectangle> candidates, Runnable nativeDraw)
	{
		int[] parents = new int[candidates.size()];
		Arrays.fill(parents, -1);
		return probePixels(pixels, width, height, root, candidates, parents, nativeDraw);
	}

	static Result probePixels(int[] pixels, int width, int height, Rectangle root,
		List<Rectangle> candidates, int[] parents, Runnable nativeDraw)
	{
		long start = System.nanoTime();
		if (pixels == null || width <= 0 || height <= 0
			|| (long) width * height > pixels.length || root == null
			|| parents.length != candidates.size())
		{
			return new Result("invalid geometry", 0, new int[0], new int[0], new int[0]);
		}
		int[][] witnesses = new int[candidates.size()][];
		int[] witnessCounts = new int[candidates.size()];
		long allocatedBytesMin = (long) candidates.size() * Integer.BYTES * 2;
		for (int i = 0; i < candidates.size(); i++)
		{
			Rectangle r = candidates.get(i).intersection(new Rectangle(0, 0, width, height));
			if (r.width <= 0 || r.height <= 0)
			{
				witnesses[i] = new int[0];
				continue;
			}
			int[] tmp = new int[r.width * r.height];
			allocatedBytesMin += (long) tmp.length * Integer.BYTES;
			int n = 0;
			for (int y = r.y + WITNESS_MARGIN; y < r.y + r.height - WITNESS_MARGIN; y++)
			{
				for (int x = r.x + WITNESS_MARGIN; x < r.x + r.width - WITNESS_MARGIN; x++)
				{
					if (containsWithMargin(root, x, y) || overlapsOther(candidates, i, x, y))
					{
						continue;
					}
					tmp[n++] = y * width + x;
				}
			}
			witnesses[i] = Arrays.copyOf(tmp, n);
			allocatedBytesMin += (long) n * Integer.BYTES;
			witnessCounts[i] = n;
		}

		long copyStart = System.nanoTime();
		int[] before = pixels.clone();
		long copyNanos = System.nanoTime() - copyStart;
		allocatedBytesMin += (long) before.length * Integer.BYTES;
		int[] changed = new int[candidates.size()];
		Rectangle rootClipped = root.intersection(new Rectangle(0, 0, width, height));
		int rootWitness = 0;
		int rootChanged = 0;
		long drawNanos = 0;
		long restoreNanos;
		boolean restored;
		try
		{
			for (int y = rootClipped.y; y < rootClipped.y + rootClipped.height; y++)
			{
				for (int x = rootClipped.x; x < rootClipped.x + rootClipped.width; x++)
				{
					if (!overlapsOther(candidates, -1, x, y))
					{
						pixels[y * width + x] = SENTINEL;
						rootWitness++;
					}
				}
			}
			for (int[] candidate : witnesses)
			{
				for (int index : candidate)
				{
					pixels[index] = SENTINEL;
				}
			}
			long drawStart = System.nanoTime();
			nativeDraw.run();
			drawNanos = System.nanoTime() - drawStart;
			for (int y = rootClipped.y; y < rootClipped.y + rootClipped.height; y++)
			{
				for (int x = rootClipped.x; x < rootClipped.x + rootClipped.width; x++)
				{
					if (!overlapsOther(candidates, -1, x, y) && pixels[y * width + x] != SENTINEL)
					{
						rootChanged++;
					}
				}
			}
			for (int i = 0; i < witnesses.length; i++)
			{
				for (int index : witnesses[i])
				{
					if (pixels[index] != SENTINEL)
					{
						changed[i]++;
					}
				}
			}
		}
		finally
		{
			long restoreStart = System.nanoTime();
			System.arraycopy(before, 0, pixels, 0, pixels.length);
			restored = Arrays.equals(before, pixels);
			restoreNanos = System.nanoTime() - restoreStart;
		}
		String status = rootWitness == 0 || anyZero(witnessCounts)
			? "ambiguous geometry" : rootChanged == 0 ? "native draw missed buffer" : "sampled";
		int[] active = "sampled".equals(status) ? chain(parents, changed) : new int[0];
		int hitCount = 0;
		for (int value : changed)
		{
			if (value > 0)
			{
				hitCount++;
			}
		}
		if ("sampled".equals(status) && hitCount != active.length)
		{
			status = "ambiguous detection";
			active = new int[0];
		}
		return new Result(status, System.nanoTime() - start, witnessCounts, changed, active,
			rootWitness, rootChanged, copyNanos, drawNanos, restoreNanos,
			before.length, allocatedBytesMin, restored);
	}

	private static boolean anyZero(int[] values)
	{
		for (int value : values)
		{
			if (value == 0)
			{
				return true;
			}
		}
		return false;
	}

	private static int[] chain(int[] parents, int[] changed)
	{
		int[] result = new int[changed.length];
		int n = 0;
		int parent = -1;
		while (true)
		{
			int next = -1;
			for (int i = 0; i < changed.length; i++)
			{
				if (changed[i] > 0 && parents[i] == parent)
				{
					if (next != -1)
					{
						return new int[0];
					}
					next = i;
				}
			}
			if (next == -1)
			{
				return Arrays.copyOf(result, n);
			}
			result[n++] = next;
			parent = next;
		}
	}

	private static boolean overlapsOther(List<Rectangle> bounds, int excluded, int x, int y)
	{
		for (int i = 0; i < bounds.size(); i++)
		{
				if (i != excluded && containsWithMargin(bounds.get(i), x, y))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean containsWithMargin(Rectangle rectangle, int x, int y)
	{
		return x >= rectangle.x - WITNESS_MARGIN && x < rectangle.x + rectangle.width + WITNESS_MARGIN
			&& y >= rectangle.y - WITNESS_MARGIN && y < rectangle.y + rectangle.height + WITNESS_MARGIN;
	}

	private static Rectangle bounds(Menu menu)
	{
		return new Rectangle(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), menu.getMenuHeight());
	}

	private static boolean collect(Menu menu, List<Rectangle> out, List<Integer> parents,
		IdentityHashMap<Menu, Boolean> seen, int parentIndex, int depth)
	{
		if (menu == null || depth > 8 || seen.put(menu, Boolean.TRUE) != null)
		{
			return false;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		if (entries == null)
		{
			return true;
		}
		for (MenuEntry entry : entries)
		{
			if (entry != null && entry.getSubMenu() != null)
			{
				Menu child = entry.getSubMenu();
				int childIndex = out.size();
				out.add(bounds(child));
				parents.add(parentIndex);
				if (!collect(child, out, parents, seen, childIndex, depth + 1))
				{
					return false;
				}
			}
		}
		return true;
	}

	public static final class Result
	{
		public final String status;
		public final long nanoseconds;
		public final int[] witnesses;
		public final int[] changed;
		/** Candidate indexes from root to deepest detected child; empty on ambiguity. */
		public final int[] activeChain;
		public final int rootWitness;
		public final int rootChanged;
		public final long copyNanos;
		public final long drawNanos;
		public final long restoreNanos;
		public final int copiedPixels;
		public final long allocatedBytesMin;
		public final boolean restored;

		private Result(String status, long nanoseconds, int[] witnesses, int[] changed)
		{
			this(status, nanoseconds, witnesses, changed, new int[0]);
		}

		private Result(String status, long nanoseconds, int[] witnesses, int[] changed, int[] activeChain)
		{
			this(status, nanoseconds, witnesses, changed, activeChain,
				0, 0, 0, 0, 0, 0, 0, false);
		}

		private Result(String status, long nanoseconds, int[] witnesses, int[] changed,
			int[] activeChain, int rootWitness, int rootChanged, long copyNanos,
			long drawNanos, long restoreNanos, int copiedPixels,
			long allocatedBytesMin, boolean restored)
		{
			this.status = status;
			this.nanoseconds = nanoseconds;
			this.witnesses = witnesses;
			this.changed = changed;
			this.activeChain = activeChain;
			this.rootWitness = rootWitness;
			this.rootChanged = rootChanged;
			this.copyNanos = copyNanos;
			this.drawNanos = drawNanos;
			this.restoreNanos = restoreNanos;
			this.copiedPixels = copiedPixels;
			this.allocatedBytesMin = allocatedBytesMin;
			this.restored = restored;
		}
	}
}
