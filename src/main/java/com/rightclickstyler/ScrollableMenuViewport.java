package com.rightclickstyler;

import java.awt.Rectangle;
import net.runelite.api.Point;

/** The visible root rows, using RuneLite's public entry scroll offset. */
final class ScrollableMenuViewport
{
	final int scroll;
	final int firstRow;
	final int endRow;
	private final Rectangle bounds;
	private final int canvasHeight;

	private ScrollableMenuViewport(Rectangle bounds, int canvasHeight, int scroll,
		int endRow)
	{
		this.bounds = bounds;
		this.canvasHeight = canvasHeight;
		this.scroll = scroll;
		this.firstRow = scroll;
		this.endRow = endRow;
	}

	static ScrollableMenuViewport capture(Rectangle bounds, int entries, int canvasWidth,
		int canvasHeight, boolean scrollable, int scroll)
	{
		if (bounds == null || entries <= 0 || canvasWidth <= 0 || canvasHeight <= 0
			|| bounds.width <= 0 || bounds.height <= 0 || bounds.x >= canvasWidth
			|| bounds.y >= canvasHeight || bounds.x + bounds.width <= 0)
		{
			return null;
		}
		if (!scrollable && scroll != 0)
		{
			return null;
		}
		if (scrollable)
		{
			// The inspected RuneLite client sets scrollability when the full native
			// menu height exceeds the canvas, and clamps the offset in 15px rows.
			int excess = bounds.height - canvasHeight;
			if (excess <= 0 || scroll < 0 || scroll >= entries
				|| scroll > ((long) excess + MenuRenderer.ROW_HEIGHT - 1) / MenuRenderer.ROW_HEIGHT)
			{
				return null;
			}
		}
		int rowSpace = canvasHeight - (bounds.y + MenuRenderer.HEADER_HEIGHT);
		if (rowSpace <= 0)
		{
			return null;
		}
		int slots = (rowSpace + MenuRenderer.ROW_HEIGHT - 1) / MenuRenderer.ROW_HEIGHT;
		int end = Math.min(entries, scroll + slots);
		return end > scroll ? new ScrollableMenuViewport(bounds, canvasHeight, scroll, end) : null;
	}

	boolean containsRow(int row)
	{
		return row >= firstRow && row < endRow;
	}

	int rowTop(int row)
	{
		return bounds.y + MenuRenderer.HEADER_HEIGHT
			+ (row - scroll) * MenuRenderer.ROW_HEIGHT;
	}

	int hoverIndex(Point mouse)
	{
		if (mouse == null || mouse.getX() < bounds.x
			|| mouse.getX() >= bounds.x + bounds.width
			|| mouse.getY() >= canvasHeight || mouse.getY() >= bounds.y + bounds.height)
		{
			return -1;
		}
		int relativeY = mouse.getY() - (bounds.y + MenuRenderer.HEADER_HEIGHT);
		if (relativeY < 0)
		{
			return -1;
		}
		int row = scroll + relativeY / MenuRenderer.ROW_HEIGHT;
		return containsRow(row) ? row : -1;
	}
}
