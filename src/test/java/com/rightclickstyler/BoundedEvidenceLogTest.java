package com.rightclickstyler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BoundedEvidenceLogTest
{
	@Test
	public void drainsInArrivalOrderAcrossTheWrap()
	{
		BoundedEvidenceLog log = new BoundedEvidenceLog(3);
		log.offer("a");
		log.offer("b");
		assertEquals(0, log.drain(new ArrayList<>()::add));
		for (String record : new String[]{"c", "d", "e"})
		{
			assertTrue(log.offer(record));
		}
		List<String> out = new ArrayList<>();
		assertEquals(0, log.drain(out::add));
		assertEquals(Arrays.asList("c", "d", "e"), out);
		assertEquals(0, log.size());
	}

	@Test
	public void overflowIsCountedAndReportedOnce()
	{
		BoundedEvidenceLog log = new BoundedEvidenceLog(2);
		assertTrue(log.offer("kept 1"));
		assertTrue(log.offer("kept 2"));
		assertFalse(log.offer("dropped 1"));
		assertFalse(log.offer("dropped 2"));
		List<String> out = new ArrayList<>();
		assertEquals(2, log.drain(out::add));
		assertEquals("the earliest evidence is kept", Arrays.asList("kept 1", "kept 2"), out);
		assertEquals("reported once", 0, log.drain(out::add));
	}

	@Test
	public void aFailingSinkLosesOnlyTheRecordItWasGiven()
	{
		BoundedEvidenceLog log = new BoundedEvidenceLog(4);
		log.offer("1");
		log.offer("2");
		log.offer("3");
		log.offer("4");
		log.offer("overflow");
		try
		{
			log.drain(record ->
			{
				if ("2".equals(record))
				{
					throw new IllegalStateException("sink");
				}
			});
			fail();
		}
		catch (IllegalStateException expected)
		{
			// the overlay disables streaming here; rendering is not involved
		}
		List<String> out = new ArrayList<>();
		assertEquals("the dropped count survives the failed drain", 1, log.drain(out::add));
		assertEquals(Arrays.asList("3", "4"), out);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsAnEmptyCapacity()
	{
		new BoundedEvidenceLog(0);
	}
}
