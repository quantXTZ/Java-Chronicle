/*
 * Copyright 2013 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.higherfrequencytrading.chronicle.impl;

import com.higherfrequencytrading.affinity.AffinityLock;
import com.higherfrequencytrading.affinity.AffinityStrategies;
import com.higherfrequencytrading.busywaiting.BusyWaiter;
import com.higherfrequencytrading.chronicle.Excerpt;

import java.io.IOException;

import static com.higherfrequencytrading.chronicle.impl.GlobalSettings.*;
import static junit.framework.Assert.assertEquals;

/**
 * @author peter.lawrey
 *         <p>
 *         on a 4.6 GHz, i7-2600, 16 GB Centos 6.2 -Dtest.size=100
 *         Took 12.893 seconds to write/read 200,000,000 entries, rate was 15.5 M entries/sec - ByteBuffer (tmpfs)
 *         Took 9.855 seconds to write/read 200,000,000 entries, rate was 20.3 M entries/sec - Using Unsafe (tmpfs)
 *         </p>
 *         Took 24.572 seconds to write/read 400,000,000 entries, rate was 16.3 M entries/sec : -Dtest.size=200 (22 GB on a 16 GB machine)
 *         Took 69.098 seconds to write/read 1,000,000,000 entries, rate was 14.5 M entries/sec -Dtest.size=500 (56 GB on a 16 GB machine)
 */
public class IndexedChronicleThroughputMain {

    public static void main(String... args) throws IOException, InterruptedException {
        final String basePath = BASE_DIR + "request";
        final String basePath2 = BASE_DIR + "response";
        deleteOnExit(basePath);
        deleteOnExit(basePath2);

        IndexedChronicle tsc = new IndexedChronicle(basePath);
        tsc.useUnsafe(USE_UNSAFE);
        IndexedChronicle tsc2 = new IndexedChronicle(basePath2);
        tsc2.useUnsafe(USE_UNSAFE);
        tsc.clear();

        AffinityLock al = AffinityLock.acquireLock(false);
        final AffinityLock al2 = al.acquireLock(AffinityStrategies.SAME_SOCKET, AffinityStrategies.DIFFERENT_CORE);

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                al2.bind();
                try {
                    StringBuilder sb = new StringBuilder();
                    final IndexedChronicle tsc = new IndexedChronicle(basePath);
                    tsc.useUnsafe(USE_UNSAFE);
                    final IndexedChronicle tsc2 = new IndexedChronicle(basePath2);
                    tsc2.useUnsafe(USE_UNSAFE);
                    tsc2.clear();

                    Excerpt excerpt = tsc.createExcerpt();
                    Excerpt excerpt2 = tsc2.createExcerpt();
                    for (int i = 0; i < RUNS; i++) {
                        do {
                            pause();
                        } while (!excerpt.index(i));

                        char type = excerpt.readChar();
                        if ('T' != type)
                            assertEquals('T', type);
                        int n = excerpt.readInt();
                        if (i != n)
                            assertEquals(i, n);
                        excerpt.readChars(sb);
                        excerpt.finish();

                        excerpt2.startExcerpt(8);
                        excerpt2.writeChar('R');
                        excerpt2.writeInt(n);
                        excerpt2.writeShort(-1);
                        excerpt2.finish();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    al2.release();
                }
            }
        });
        t.start();

        al.bind();
        Excerpt excerpt = tsc.createExcerpt();
        Excerpt excerpt2 = tsc2.createExcerpt();
        long start = System.nanoTime();
        int i2 = 0;
        for (int i = 0; i < RUNS; i++) {
            excerpt.startExcerpt(32);
            excerpt.writeChar('T');
            excerpt.writeInt(i);
            excerpt.writeChars("Hello World!");
            excerpt.finish();

            while (excerpt2.index(i2)) {
                char type = excerpt2.readChar();
                if ('R' != type)
                    assertEquals('R', type);
                int n = excerpt2.readInt();
                if (i2 != n)
                    assertEquals(i2, n);
                excerpt2.readShort();
                excerpt2.finish();
                i2++;
            }
        }

        for (; i2 < RUNS; i2++) {
            do {
                pause();
            } while (!excerpt2.index(i2));
            char type = excerpt2.readChar();
            if ('R' != type)
                assertEquals('R', type);
            int n = excerpt2.readInt();
            if (i2 != n)
                assertEquals(i2, n);
            excerpt2.finish();
        }

        t.join();
        long time = System.nanoTime() - start;
        tsc.close();
        tsc2.close();
        System.out.printf("Took %.3f seconds to write/read %,d entries, rate was %.1f M entries/sec%n", time / 1e9, 2 * RUNS, 2 * RUNS * 1e3 / time);
        al.release();
    }

    private static void pause() {
        BusyWaiter.pause();
    }
}
