package io.zeebe.hashindex;

import static org.agrona.BitUtil.SIZE_OF_BYTE;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.hashindex.store.FileChannelIndexStore;
import org.junit.Before;
import org.junit.Test;

public class HashIndexConsistencyTest
{
    private static final byte[] VALUE = "bar".getBytes();
    private Long2BytesHashIndex index;
    private FileChannelIndexStore indexStore;
    protected static final int BLOCK_LENGTH = 16;
    protected static final int NUM_BLOCKS = 16;
    protected static final int VALUE_LENGTH = 3 * SIZE_OF_BYTE;


    @Before
    public void createIndex()
    {
        final int indexSize = NUM_BLOCKS * BLOCK_LENGTH;

        indexStore = FileChannelIndexStore.tempFileIndexStore();
        index = new Long2BytesHashIndex(indexStore, indexSize, BLOCK_LENGTH, VALUE_LENGTH);
    }


    @Test
    public void shouldFlushWithMoreValuesThanContainedInSingleBlock()
    {
        final int numEntries = BLOCK_LENGTH + 1;

        for (int i = 0; i < numEntries; i++)
        {
            index.put(i, VALUE);
        }

        // this is done in the broker when a snapshot is written
        index.flush();

        final byte[] value = new byte[VALUE_LENGTH];
        for (int i = 0; i < numEntries; i++)
        {
            assertThat(index.get(i, value)).isTrue();
            assertThat(value).isEqualTo(VALUE);
        }
    }

    @Test
    public void shouldNotOccurIllegalArgumentException()
    {
        final int numEntries = 3 * BLOCK_LENGTH;

        for (int i = 0; i < numEntries; i++)
        {
            index.flush();
            index.put(i, VALUE);
        }


        final byte[] value = new byte[VALUE_LENGTH];
        for (int i = 0; i < numEntries; i++)
        {
            assertThat(index.get(i, value)).isTrue();
            assertThat(value).isEqualTo(VALUE);
        }
    }


}
