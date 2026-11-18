package com.rightclickstyler;

import java.util.function.Consumer;

/**
 * A bounded queue of compact diagnostic records, drained on the client's game tick so that
 * formatting and logging stay off the frame being prepared. Overflow is never silent: a full
 * queue refuses the newest record and counts it, and the next drain returns that count so the
 * caller can report it. Diagnostic only; nothing here can reach rendering.
 */
final class BoundedEvidenceLog
{
	private final String[] records;
	private int head;
	private int size;
	private long dropped;

	BoundedEvidenceLog(int capacity)
	{
		if (capacity <= 0)
		{
			throw new IllegalArgumentException("capacity=" + capacity);
		}
		records = new String[capacity];
	}

	/** @return false, counting the record as dropped, when the queue is full */
	boolean offer(String record)
	{
		if (size == records.length)
		{
			dropped++;
			return false;
		}
		records[(head + size) % records.length] = record;
		size++;
		return true;
	}

	/**
	 * Hands every queued record to {@code sink} in arrival order and empties the queue. Each
	 * record leaves the queue before the sink sees it, so a sink that throws loses only that
	 * record; the rest, and the dropped count, wait for the next drain.
	 *
	 * @return how many records were dropped since the previous drain
	 */
	long drain(Consumer<String> sink)
	{
		while (size > 0)
		{
			String record = records[head];
			records[head] = null;
			head = (head + 1) % records.length;
			size--;
			sink.accept(record);
		}
		long lost = dropped;
		dropped = 0;
		return lost;
	}

	int size()
	{
		return size;
	}

	int capacity()
	{
		return records.length;
	}
}
