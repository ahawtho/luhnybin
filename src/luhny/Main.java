package luhny;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The main things I believe distinguish this implementation are:
 * <div style='white-space: pre-wrap'>
 * 1. It keeps an incremental sum of the digits
 * 2. It keeps two 'history' buffers that allow updating each sum only incrementally.
 * 3. It only does allocations during startup<sup>*</sup>.
 * 4. It supports more than 14-16 digits.
 * </div>
 *
 * I use an idiom in this class that simplifies what would normally be a ring
 * buffer. Rather than require the division on each array access, I keep two
 * copies of the data back to back, e.g.:
 * <pre>
 * [0, 1, 2, 3, 0, 1, 2, 3]
 * </pre>
 * <p>
 * This allows me to look backwards in the buffer when at the front
 * without actually having to wrap around. The buffers in this class should be
 * small enough to avoid cache misses in most cases.
 * <p>
 * I also do all the replacement in-place, so no buffers were harmed (allocated)
 * in the running of this program (after initialization).  I ran the program
 * with -Xmx8m -Xms8m and after 10000 runs I still don't have any GC's. This
 * shouldn't contribute significantly to GC.
 * <p>
 * <sup>*</sup>If there are an excessive number of separator chars ('-', ' '),
 * the ByteArrayOutputStream used for the holding buffer will grow to hold them,
 * but this is a pretty pathological case.
 *
 * @author ahawtho
 */
public class Main {
    private static final int MIN = 14, MAX = 16;

    /**
     * @param args
     * @throws IOException
     */
    public static void main(final String[] args) throws IOException {
        // Treating bytes as char values since the input is US-ASCII.

        // Holds the absolute index in the holding buffer
        final long[] idxbuf = new long[MAX * 2];
        int idxoff = MAX;
        final LuhnUpdater updater = new LuhnUpdater();

        // Holds the buffered output digits until we're ready to push them
        // out to the world.
        final ByteArrayExposé holding = new ByteArrayExposé();
        long bytesWritten = 0;
        final BufferedOutputStream output =
            new BufferedOutputStream(System.out);

        boolean continue_ = true;
        final BufferedInputStream in = new BufferedInputStream(System.in);
        int b;
        for (long i = 0; continue_; ++i) {
            b = in.read();
            switch (b) {
                case '0': case '1': case '2': case '3': case '4':
                case '5': case '6': case '7': case '8': case '9':
                    updater.append(b - '0');
                    idxbuf[idxoff] = (idxbuf[idxoff - MAX] = i);
                    holding.write(b);
                    for (int j = Math.min(MAX, updater.total()); j >= MIN; --j) {
                        if (updater.calculate(j)) {
                            for (int k = idxoff; k >= idxoff - j + 1; --k) {
                                final long totalIdx = idxbuf[k];
                                final int baOff = (int)(totalIdx - bytesWritten);
                                holding.bytes()[baOff] = 'X';
                                if (baOff == 0)
                                    break;
                            }
                            break;
                        }
                    }
                    if (idxoff == MAX * 2 - 1)
                        idxoff = MAX;
                    else
                        idxoff++;
                    break;
                case '-': case ' ':
                    // Only need to write to holder if we've got digits in the
                    // queue.
                    if (updater.total() > 0) {
                        holding.write(b);
                    } else {
                        output.write(b);
                        ++bytesWritten;
                    }
                    break;
                default:
                    if (updater.total() > 0) {
                        holding.writeTo(output);
                        bytesWritten += holding.count();
                        holding.reset();
                        updater.reset();
                    }
                    // EOF
                    if (b < 0)
                        continue_ = false;
                    else {
                        output.write(b);
                        ++bytesWritten;
                    }
                    break;
            }
            // This works around the hang, we have to flush every now and then
            // if we want Bob's test to see our output.
            if (in.available() == 0)
                output.flush();
        }
        output.close();
    }

    /**
     * Keeps track of two buffers of digits and the sums of those digits for
     * MIN..MAX values (MIN=14, MAX=16 for this test). The 'current' buffer
     * stores the luhn digits regarding the last digit as the rightmost digit,
     * while the 'alternate' buffer stores the luhn digits regarding a
     * hypothetical next digit as the rightmost digit.
     * <p>
     * Each time a digit is appended to an instance of this class, these buffers
     * are swapped to allow them to correctly keep track of historical data.
     *
     * @author ahawtho
     */
    static class LuhnUpdater {
        LuhnBuffer m_current = new LuhnBuffer();
        LuhnBuffer m_alternate = new LuhnBuffer();

        /**
         * the idea
         * @param p_digit
         */
        void append(final int p_digit) {
            final LuhnBuffer tmp = m_current;
            m_current = m_alternate;
            m_alternate = tmp;

            final int alt = p_digit < 5
                      ? p_digit * 2 // doubled
                      : ((p_digit - 5) * 2) + 1; // doubled & digits summed.

            m_current.append(p_digit);
            m_alternate.append(alt);
        }

        boolean calculate(final int p_len) {
            return m_current.calculate(p_len);
        }

        int total() {
            return m_current.total();
        }

        void reset() {
            m_current.reset();
            m_alternate.reset();
        }
    }

    /**
     * A class that stores a buffer of 'luhn' digits and incrementally computes
     * the sum of those digits for MIN..MAX previous digits while it's updated.
     * This is written so it can support any number of sums by parameterizing
     * MIN and MAX.
     *
     * @author ahawtho
     */
    static class LuhnBuffer {
        /** The buffer of components duplicated for easier indexing */
        int[] m_buffer = new int[MAX * 2];
        /** luhn sums for MIN..MAX */
        int[] m_sums = new int[MAX - MIN + 1];
        /** stores the offset into the component buffer */
        int m_offset = MAX;
        int m_total = 0;

        boolean calculate(final int p_len) {
            if (p_len > MAX)
                throw new IndexOutOfBoundsException(p_len + " > " + MAX);
            if (p_len < MIN)
                throw new IndexOutOfBoundsException(p_len + " < " + MIN);
            boolean ret;
            if (m_total < p_len)
                ret = false;
            else
                ret = m_sums[p_len - MIN] % 10 == 0;
            return ret;
        }

        void append(final int p_modifiedDigit) {
            final int total = ++m_total;
            for (int i = 0; i < (MAX - MIN + 1); ++i) {
                if (total >= MIN + i)
                    m_sums[i] -= m_buffer[m_offset - (MIN + i)];
                m_sums[i] += p_modifiedDigit;
            }
            m_buffer[m_offset] = p_modifiedDigit;
            m_buffer[m_offset - MAX] = p_modifiedDigit;

            if (m_offset == MAX * 2 - 1)
                m_offset = MAX;
            else
                m_offset++;
        }

        int total() {
            return m_total;
        }

        void reset() {
            Arrays.fill(m_buffer, 0);
            Arrays.fill(m_sums, 0);
            m_offset = MAX;
            m_total = 0;
        }
    }

    /**
     * Allow access to the bytes so they can be replaced without copying.
     *
     * @author ahawtho
     */
    private static class ByteArrayExposé extends ByteArrayOutputStream {
        byte[] bytes() {
            return buf;
        }

        int count() {
            return count;
        }
    }
}
