package com.rightclickstyler;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PerfTraceTest
{
	/** Collects formatted INFO lines. */
	private static final class CapturingLogger extends MarkerIgnoringBase
	{
		private final List<String> lines = new ArrayList<>();

		@Override
		public void info(String format, Object... arguments)
		{
			lines.add(MessageFormatter.arrayFormat(format, arguments).getMessage());
		}

		@Override public boolean isTraceEnabled() { return false; }
		@Override public void trace(String msg) { }
		@Override public void trace(String format, Object arg) { }
		@Override public void trace(String format, Object arg1, Object arg2) { }
		@Override public void trace(String format, Object... arguments) { }
		@Override public void trace(String msg, Throwable t) { }
		@Override public boolean isDebugEnabled() { return false; }
		@Override public void debug(String msg) { }
		@Override public void debug(String format, Object arg) { }
		@Override public void debug(String format, Object arg1, Object arg2) { }
		@Override public void debug(String format, Object... arguments) { }
		@Override public void debug(String msg, Throwable t) { }
		@Override public boolean isInfoEnabled() { return true; }
		@Override public void info(String msg) { lines.add(msg); }
		@Override public void info(String format, Object arg) { info(format, new Object[]{arg}); }
		@Override public void info(String format, Object arg1, Object arg2) { info(format, new Object[]{arg1, arg2}); }
		@Override public void info(String msg, Throwable t) { lines.add(msg); }
		@Override public boolean isWarnEnabled() { return false; }
		@Override public void warn(String msg) { }
		@Override public void warn(String format, Object arg) { }
		@Override public void warn(String format, Object... arguments) { }
		@Override public void warn(String format, Object arg1, Object arg2) { }
		@Override public void warn(String msg, Throwable t) { }
		@Override public boolean isErrorEnabled() { return false; }
		@Override public void error(String msg) { }
		@Override public void error(String format, Object arg) { }
		@Override public void error(String format, Object arg1, Object arg2) { }
		@Override public void error(String format, Object... arguments) { }
		@Override public void error(String msg, Throwable t) { }
	}

	@Test
	public void reportsOneWindowWithFramesCostsAllocationAndGc()
	{
		PerfTrace perf = new PerfTrace();
		CapturingLogger log = new CapturingLogger();
		long now = 1_000_000_000L;
		perf.report(log, "imageCache=on", now); // starts the window
		for (int i = 0; i < 100; i++)
		{
			now += 7_000_000L;
			perf.frame(i % 2 == 0 ? PerfTrace.Frame.SUBMENU_THEMED : PerfTrace.Frame.MENU_CLOSED, 144, now);
			perf.cost(PerfTrace.Cost.PREPARE_WITH_CHILD, 3_000_000L + i);
			perf.cost(PerfTrace.Cost.PREPARE_ALLOCATED_BYTES, 30_000L);
		}
		perf.report(log, "imageCache=on", now + 1_000_000L);
		assertEquals("no report inside the 5s window", 0, log.lines.size());
		perf.report(log, "imageCache=on", now + 6_000_000_000L);
		assertEquals(1, log.lines.size());
		String line = log.lines.get(0);
		// The first frame has no previous frame, so it starts the interval but is not counted.
		assertTrue(line, line.contains("SUBMENU_THEMED{n=49 intervalMs p50=7.00"));
		assertTrue(line, line.contains("MENU_CLOSED{n=50"));
		assertTrue(line, line.contains("clientFps p50=144"));
		assertTrue(line, line.contains("PREPARE_WITH_CHILD{n=100 us p50=3000"));
		assertTrue(line, line.contains("PREPARE_ALLOCATED_BYTES{n=100 bytes p50=30000"));
		assertTrue(line, line.contains("gcCount="));
		assertTrue(line, line.endsWith("| imageCache=on"));
		perf.report(log, null, now + 12_000_000_000L);
		assertEquals("windows reset after reporting", 2, log.lines.size());
		assertTrue(log.lines.get(1), !log.lines.get(1).contains("SUBMENU_THEMED{"));
	}
}
