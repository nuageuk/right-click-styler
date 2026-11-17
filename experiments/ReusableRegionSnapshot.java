package experiments;

import java.awt.Rectangle;
import java.util.List;

/**
 * Isolated copy-cost experiment. It is not used by the live probe because native
 * drawing outside menu bounds has not yet been ruled out in a running client.
 */
final class ReusableRegionSnapshot
{
	private final Rectangle[] regions;
	private final int[] offsets;
	private final int[] saved;
	private final int stride;

	ReusableRegionSnapshot(int width, int height, List<Rectangle> menuBounds)
	{
		stride = width;
		regions = new Rectangle[menuBounds.size()];
		offsets = new int[menuBounds.size()];
		Rectangle canvas = new Rectangle(0, 0, width, height);
		int total = 0;
		for (int i = 0; i < regions.length; i++)
		{
			Rectangle clipped = menuBounds.get(i).intersection(canvas);
			regions[i] = clipped.isEmpty() ? new Rectangle() : clipped;
			offsets[i] = total;
			total = Math.addExact(total, Math.multiplyExact(regions[i].width, regions[i].height));
		}
		saved = new int[total];
	}

	int copiedPixels()
	{
		return saved.length;
	}

	void capture(int[] pixels)
	{
		copy(pixels, true);
	}

	void restore(int[] pixels)
	{
		copy(pixels, false);
	}

	private void copy(int[] pixels, boolean capture)
	{
		for (int i = 0; i < regions.length; i++)
		{
			Rectangle r = regions[i];
			int offset = offsets[i];
			for (int y = 0; y < r.height; y++)
			{
				int frameIndex = (r.y + y) * stride + r.x;
				int savedIndex = offset + y * r.width;
				if (capture)
				{
					System.arraycopy(pixels, frameIndex, saved, savedIndex, r.width);
				}
				else
				{
					System.arraycopy(saved, savedIndex, pixels, frameIndex, r.width);
				}
			}
		}
	}
}
