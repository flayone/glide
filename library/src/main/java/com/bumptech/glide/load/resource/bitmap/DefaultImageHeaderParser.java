package com.bumptech.glide.load.resource.bitmap;

import static com.bumptech.glide.load.ImageHeaderParser.ImageType.ANIMATED_AVIF;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.ANIMATED_WEBP;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.AVIF;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.GIF;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.JPEG;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.PNG;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.PNG_A;
import static com.bumptech.glide.load.ImageHeaderParser.ImageType.UNKNOWN;

import android.util.Log;
import androidx.annotation.NonNull;
import com.bumptech.glide.load.ImageHeaderParser;
import com.bumptech.glide.load.engine.bitmap_recycle.ArrayPool;
import com.bumptech.glide.util.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

/** A class for parsing the exif orientation and other data from an image header. */
public final class DefaultImageHeaderParser implements ImageHeaderParser {
  // Due to https://code.google.com/p/android/issues/detail?id=97751.
  // TAG needs to be under 23 chars, so "Default" > "Dflt".
  private static final String TAG = "DfltImageHeaderParser";

  private static final int GIF_HEADER = 0x474946;
  private static final int PNG_HEADER = 0x89504E47;
  static final int EXIF_MAGIC_NUMBER = 0xFFD8;
  // "MM".
  private static final int MOTOROLA_TIFF_MAGIC_NUMBER = 0x4D4D;
  // "II".
  private static final int INTEL_TIFF_MAGIC_NUMBER = 0x4949;
  private static final String JPEG_EXIF_SEGMENT_PREAMBLE = "Exif\0\0";
  static final byte[] JPEG_EXIF_SEGMENT_PREAMBLE_BYTES =
      JPEG_EXIF_SEGMENT_PREAMBLE.getBytes(Charset.forName("UTF-8"));
  private static final String JPEG_MPF_SEGMENT_PREAMBLE = "MPF";
  static final byte[] JPEG_MPF_SEGMENT_PREAMBLE_BYTES =
      JPEG_MPF_SEGMENT_PREAMBLE.getBytes(Charset.forName("UTF-8"));
  private static final int SEGMENT_SOS = 0xDA;
  private static final int MARKER_EOI = 0xD9;
  static final int SEGMENT_START_ID = 0xFF;
  static final int EXIF_SEGMENT_TYPE = 0xE1;
  static final int APP2_SEGMENT_TYPE = 0xE2;
  private static final int ORIENTATION_TAG_TYPE = 0x0112;
  private static final int[] BYTES_PER_FORMAT = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};
  // WebP-related
  // "RIFF"
  private static final int RIFF_HEADER = 0x52494646;
  // "WEBP"
  private static final int WEBP_HEADER = 0x57454250;
  // "VP8" null.
  private static final int VP8_HEADER = 0x56503800;
  private static final int VP8_HEADER_MASK = 0xFFFFFF00;
  private static final int VP8_HEADER_TYPE_MASK = 0x000000FF;
  // 'X'
  private static final int VP8_HEADER_TYPE_EXTENDED = 0x00000058;
  // 'L'
  private static final int VP8_HEADER_TYPE_LOSSLESS = 0x0000004C;
  private static final int WEBP_EXTENDED_ANIMATION_FLAG = 1 << 1;
  private static final int WEBP_EXTENDED_ALPHA_FLAG = 1 << 4;
  private static final int WEBP_LOSSLESS_ALPHA_FLAG = 1 << 3;
  // Avif-related
  // "ftyp"
  private static final int FTYP_HEADER = 0x66747970;
  // "avif"
  private static final int AVIF_BRAND = 0x61766966;
  // "avis"
  private static final int AVIS_BRAND = 0x61766973;

  @NonNull
  @Override
  public ImageType getType(@NonNull InputStream is) throws IOException {
    return getType(new StreamReader(Preconditions.checkNotNull(is)));
  }

  @NonNull
  @Override
  public ImageType getType(@NonNull ByteBuffer byteBuffer) throws IOException {
    return getType(new ByteBufferReader(Preconditions.checkNotNull(byteBuffer)));
  }

  @Override
  public int getOrientation(@NonNull InputStream is, @NonNull ArrayPool byteArrayPool)
      throws IOException {
    return getOrientation(
        new StreamReader(Preconditions.checkNotNull(is)),
        Preconditions.checkNotNull(byteArrayPool));
  }

  @Override
  public int getOrientation(@NonNull ByteBuffer byteBuffer, @NonNull ArrayPool byteArrayPool)
      throws IOException {
    return getOrientation(
        new ByteBufferReader(Preconditions.checkNotNull(byteBuffer)),
        Preconditions.checkNotNull(byteArrayPool));
  }

  @Override
  public boolean hasJpegMpf(@NonNull InputStream is, @NonNull ArrayPool byteArrayPool)
      throws IOException {
    return hasJpegMpf(
        new StreamReader(Preconditions.checkNotNull(is)),
        Preconditions.checkNotNull(byteArrayPool));
  }

  @Override
  public boolean hasJpegMpf(@NonNull ByteBuffer byteBuffer, @NonNull ArrayPool byteArrayPool)
      throws IOException {
    return hasJpegMpf(
        new ByteBufferReader(Preconditions.checkNotNull(byteBuffer)),
        Preconditions.checkNotNull(byteArrayPool));
  }

  private boolean hasJpegMpf(@NonNull Reader reader, @NonNull ArrayPool byteArrayPool)
      throws IOException {
    if (getType(reader) != JPEG) {
      return false;
    }
    int app2SegmentLength = moveToApp2SegmentAndGetLength(reader);
    while (app2SegmentLength > 0) {
      byte[] app2Data = byteArrayPool.get(app2SegmentLength, byte[].class);
      try {
        boolean hasJpegMpfPreamble = hasJpegMpfPreamble(reader, app2Data, app2SegmentLength);
        if (hasJpegMpfPreamble) {
          return true;
        }
      } finally {
        byteArrayPool.put(app2Data);
      }
      app2SegmentLength = moveToApp2SegmentAndGetLength(reader);
    }
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      Log.v(
          TAG,
          "hasMpf: Failed to parse APP2 segment length, or no APP2 segment with MPF metadata not"
              + " found");
    }
    return false;
  }

  @NonNull
  private ImageType getType(Reader reader) throws IOException {
    try {
      final int firstTwoBytes = reader.getUInt16();
      // JPEG.
      if (firstTwoBytes == EXIF_MAGIC_NUMBER) {
        return JPEG;
      }

      final int firstThreeBytes = (firstTwoBytes << 8) | reader.getUInt8();
      if (firstThreeBytes == GIF_HEADER) {
        return GIF;
      }

      final int firstFourBytes = (firstThreeBytes << 8) | reader.getUInt8();
      // PNG.
      if (firstFourBytes == PNG_HEADER) {
        // See: http://stackoverflow.com/questions/2057923/how-to-check-a-png-for-grayscale-alpha
        // -color-type
        reader.skip(25 - 4);
        try {
          int alpha = reader.getUInt8();
          // A RGB indexed PNG can also have transparency. Better safe than sorry!
          return alpha >= 3 ? PNG_A : PNG;
        } catch (Reader.EndOfFileException e) {
          // TODO(b/143917798): Re-enable this logging when dependent tests are fixed.
          // if (Log.isLoggable(TAG, Log.ERROR)) {
          //   Log.e(TAG, "Unexpected EOF, assuming no alpha", e);
          // }
          return PNG;
        }
      }

      if (firstFourBytes != RIFF_HEADER) {
        // Check for AVIF (reads up to 32 bytes). If it is a valid AVIF stream, then the
        // firstFourBytes will be the size of the FTYP box.
        return sniffAvif(reader, /* boxSize= */ firstFourBytes);
      }

      // WebP (reads up to 21 bytes).
      // See https://developers.google.com/speed/webp/docs/riff_container for details.
      // Bytes 4 - 7 contain length information. Skip these.
      reader.skip(4);
      final int thirdFourBytes = (reader.getUInt16() << 16) | reader.getUInt16();
      if (thirdFourBytes != WEBP_HEADER) {
        return UNKNOWN;
      }
      final int fourthFourBytes = (reader.getUInt16() << 16) | reader.getUInt16();
      if ((fourthFourBytes & VP8_HEADER_MASK) != VP8_HEADER) {
        return UNKNOWN;
      }
      if ((fourthFourBytes & VP8_HEADER_TYPE_MASK) == VP8_HEADER_TYPE_EXTENDED) {
        // Skip some more length bytes and check for transparency/alpha flag.
        reader.skip(4);
        short flags = reader.getUInt8();
        if ((flags & WEBP_EXTENDED_ANIMATION_FLAG) != 0) {
          return ANIMATED_WEBP;
        } else if ((flags & WEBP_EXTENDED_ALPHA_FLAG) != 0) {
          return ImageType.WEBP_A;
        } else {
          return ImageType.WEBP;
        }
      }
      if ((fourthFourBytes & VP8_HEADER_TYPE_MASK) == VP8_HEADER_TYPE_LOSSLESS) {
        // See chromium.googlesource.com/webm/libwebp/+/master/doc/webp-lossless-bitstream-spec.txt
        // for more info.
        reader.skip(4);
        short flags = reader.getUInt8();
        return (flags & WEBP_LOSSLESS_ALPHA_FLAG) != 0 ? ImageType.WEBP_A : ImageType.WEBP;
      }
      return ImageType.WEBP;
    } catch (Reader.EndOfFileException e) {
      // TODO(b/143917798): Re-enable this logging when dependent tests are fixed.
      // if (Log.isLoggable(TAG, Log.ERROR)) {
      //   Log.e(TAG, "Unexpected EOF", e);
      // }
      return UNKNOWN;
    }
  }

  /**
   * Check if the bits look like an AVIF Image. AVIF Specification:
   * https://aomediacodec.github.io/av1-avif/
   *
   * @return AVIF or ANIMATED_AVIF if the first few bytes look like it could be an AVIF Image or an
   *     animated AVIF Image respectively, UNKNOWN otherwise.
   */
  private ImageType sniffAvif(Reader reader, int boxSize) throws IOException {
    int chunkType = (reader.getUInt16() << 16) | reader.getUInt16();
    if (chunkType != FTYP_HEADER) {
      return UNKNOWN;
    }
    // majorBrand.
    int brand = (reader.getUInt16() << 16) | reader.getUInt16();
    // The overall logic is that, if any of the brands are 'avis', then we can conclude immediately
    // that it is an animated AVIF image. Otherwise, we conclude after seeing all the brands that if
    // one of them is 'avif', the it is a still AVIF image.
    if (brand == AVIS_BRAND) {
      return ANIMATED_AVIF;
    }
    boolean avifBrandSeen = brand == AVIF_BRAND;
    // Skip the minor version.
    reader.skip(4);
    // Check the first five minor brands. While there could theoretically be more than five minor
    // brands, it is rare in practice. This way we stop the loop from running several times on a
    // blob that just happened to look like an ftyp box.
    int sizeRemaining = boxSize - 16;
    if (sizeRemaining % 4 == 0) {
      for (int i = 0; i < 5 && sizeRemaining > 0; ++i, sizeRemaining -= 4) {
        brand = (reader.getUInt16() << 16) | reader.getUInt16();
        if (brand == AVIS_BRAND) {
          return ANIMATED_AVIF;
        } else if (brand == AVIF_BRAND) {
          avifBrandSeen = true;
        }
      }
    }
    return avifBrandSeen ? AVIF : UNKNOWN;
  }

  /**
   * Parse the orientation from the image header. If it doesn't handle this image type (or this is
   * not an image) it will return a default value rather than throwing an exception.
   *
   * @return The exif orientation if present or -1 if the header couldn't be parsed or doesn't
   *     contain an orientation
   */
  private int getOrientation(Reader reader, ArrayPool byteArrayPool) throws IOException {
    try {
      final int magicNumber = reader.getUInt16();

      if (!handles(magicNumber)) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Parser doesn't handle magic number: " + magicNumber);
        }
        return UNKNOWN_ORIENTATION;
      } else {
        int exifSegmentLength = moveToExifSegmentAndGetLength(reader);
        if (exifSegmentLength == -1) {
          if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Failed to parse exif segment length, or exif segment not found");
          }
          return UNKNOWN_ORIENTATION;
        }

        byte[] exifData = byteArrayPool.get(exifSegmentLength, byte[].class);
        try {
          return parseExifSegment(reader, exifData, exifSegmentLength);
        } finally {
          byteArrayPool.put(exifData);
        }
      }
    } catch (Reader.EndOfFileException e) {
      // TODO(b/143917798): Re-enable this logging when dependent tests are fixed.
      // if (Log.isLoggable(TAG, Log.ERROR)) {
      //   Log.e(TAG, "Unexpected EOF", e);
      // }
      return UNKNOWN_ORIENTATION;
    }
  }

  private int parseExifSegment(Reader reader, byte[] tempArray, int exifSegmentLength)
      throws IOException {
    int read = reader.read(tempArray, exifSegmentLength);
    if (read != exifSegmentLength) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(
            TAG,
            "Unable to read exif segment data"
                + ", length: "
                + exifSegmentLength
                + ", actually read: "
                + read);
      }
      return UNKNOWN_ORIENTATION;
    }

    boolean hasJpegExifPreamble = hasJpegExifPreamble(tempArray, exifSegmentLength);
    if (hasJpegExifPreamble) {
      return parseExifSegment(new RandomAccessReader(tempArray, exifSegmentLength));
    } else {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Missing jpeg exif preamble");
      }
      return UNKNOWN_ORIENTATION;
    }
  }

  private boolean hasJpegExifPreamble(byte[] exifData, int exifSegmentLength) {
    return hasMatchingBytes(exifData, exifSegmentLength, JPEG_EXIF_SEGMENT_PREAMBLE_BYTES);
  }

  private boolean hasMatchingBytes(byte[] bytes, int byteLength, byte[] bytesToMatch) {
    boolean result = bytes != null && bytesToMatch != null && byteLength > bytesToMatch.length;
    if (result) {
      for (int i = 0; i < bytesToMatch.length; i++) {
        if (bytes[i] != bytesToMatch[i]) {
          result = false;
          break;
        }
      }
    }
    return result;
  }

  /**
   * Moves reader to the start of the exif segment and returns the length of the exif segment or
   * {@code -1} if no exif segment is found.
   */
  private int moveToExifSegmentAndGetLength(Reader reader) throws IOException {
    return moveToSegmentAndGetLength(reader, EXIF_SEGMENT_TYPE);
  }

  /**
   * Returns whether the reader, set at the beginning of the APP2 segment past the length bytes,
   * contains multi-picture format (MPF) data.
   *
   * @param reader must be set at the start of an APP2 segment, past the APP2 label and length
   *     bytes.
   * @param tempArray for storing temporary array. Must be at least the size of {@code
   *     app2SegmentLength}.
   * @param app2SegmentLength the length of the APP2 segment.
   * @throws IOException if an EOF is reached before anything was read.
   */
  private boolean hasJpegMpfPreamble(Reader reader, byte[] tempArray, int app2SegmentLength)
      throws IOException {
    int read = reader.read(tempArray, app2SegmentLength);
    if (read != app2SegmentLength) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(
            TAG,
            "Unable to read APP2 segment data"
                + ", length: "
                + app2SegmentLength
                + ", actually read: "
                + read);
      }
      return false;
    }
    return hasMatchingBytes(tempArray, app2SegmentLength, JPEG_MPF_SEGMENT_PREAMBLE_BYTES);
  }

  private int moveToApp2SegmentAndGetLength(Reader reader) throws IOException {
    return moveToSegmentAndGetLength(reader, APP2_SEGMENT_TYPE);
  }

  /**
   * Moves reader to the start of the segment identified by the segment type (e.g., "0xE1" for APP1
   * and returns the length of the exif segment or {@code -1} if no segment of that type is found.
   */
  private int moveToSegmentAndGetLength(Reader reader, int requestedSegmentType)
      throws IOException {
    while (true) {
      short segmentId = reader.getUInt8();
      if (segmentId != SEGMENT_START_ID) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Unknown segmentId=" + segmentId);
        }
        return -1;
      }

      short segmentType = reader.getUInt8();
      if (segmentType == SEGMENT_SOS) {
        return -1;
      } else if (segmentType == MARKER_EOI) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Found MARKER_EOI in " + requestedSegmentType + " segment");
        }
        return -1;
      }

      int segmentLength = reader.getUInt16();
      // A segment includes the bytes that specify its length.
      int segmentContentsLength = segmentLength - 2;
      if (segmentType != requestedSegmentType) {
        long skipped = reader.skip(segmentContentsLength);
        if (skipped != segmentContentsLength) {
          if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "Unable to skip enough data"
                    + ", type: "
                    + segmentType
                    + ", wanted to skip: "
                    + segmentContentsLength
                    + ", but actually skipped: "
                    + skipped);
          }
          return -1;
        }
      } else {
        return segmentContentsLength;
      }
    }
  }

  private static int parseExifSegment(RandomAccessReader segmentData) {
    final int headerOffsetSize = JPEG_EXIF_SEGMENT_PREAMBLE.length();

    short byteOrderIdentifier = segmentData.getInt16(headerOffsetSize);
    final ByteOrder byteOrder;
    switch (byteOrderIdentifier) {
      case MOTOROLA_TIFF_MAGIC_NUMBER:
        byteOrder = ByteOrder.BIG_ENDIAN;
        break;
      case INTEL_TIFF_MAGIC_NUMBER:
        byteOrder = ByteOrder.LITTLE_ENDIAN;
        break;
      default:
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Unknown endianness = " + byteOrderIdentifier);
        }
        byteOrder = ByteOrder.BIG_ENDIAN;
        break;
    }

    segmentData.order(byteOrder);

    int firstIfdOffset = segmentData.getInt32(headerOffsetSize + 4) + headerOffsetSize;
    int tagCount = segmentData.getInt16(firstIfdOffset);
    for (int i = 0; i < tagCount; i++) {
      final int tagOffset = calcTagOffset(firstIfdOffset, i);

      final int tagType = segmentData.getInt16(tagOffset);
      // We only want orientation.
      if (tagType != ORIENTATION_TAG_TYPE) {
        continue;
      }

      final int formatCode = segmentData.getInt16(tagOffset + 2);
      // 12 is max format code.
      if (formatCode < 1 || formatCode > 12) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Got invalid format code = " + formatCode);
        }
        continue;
      }

      final int componentCount = segmentData.getInt32(tagOffset + 4);
      if (componentCount < 0) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Negative tiff component count");
        }
        continue;
      }

      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(
            TAG,
            "Got tagIndex="
                + i
                + " tagType="
                + tagType
                + " formatCode="
                + formatCode
                + " componentCount="
                + componentCount);
      }

      final int byteCount = componentCount + BYTES_PER_FORMAT[formatCode];
      if (byteCount > 4) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Got byte count > 4, not orientation, continuing, formatCode=" + formatCode);
        }
        continue;
      }

      final int tagValueOffset = tagOffset + 8;
      if (tagValueOffset < 0 || tagValueOffset > segmentData.length()) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Illegal tagValueOffset=" + tagValueOffset + " tagType=" + tagType);
        }
        continue;
      }

      if (byteCount < 0 || tagValueOffset + byteCount > segmentData.length()) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
          Log.d(TAG, "Illegal number of bytes for TI tag data tagType=" + tagType);
        }
        continue;
      }

      // assume componentCount == 1 && fmtCode == 3
      return segmentData.getInt16(tagValueOffset);
    }

    return -1;
  }

  private static int calcTagOffset(int ifdOffset, int tagIndex) {
    return ifdOffset + 2 + 12 * tagIndex;
  }

  private static boolean handles(int imageMagicNumber) {
    return (imageMagicNumber & EXIF_MAGIC_NUMBER) == EXIF_MAGIC_NUMBER
        || imageMagicNumber == MOTOROLA_TIFF_MAGIC_NUMBER
        || imageMagicNumber == INTEL_TIFF_MAGIC_NUMBER;
  }

  private static final class RandomAccessReader {
    private final ByteBuffer data;

    RandomAccessReader(byte[] data, int length) {
      this.data = (ByteBuffer) ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN).limit(length);
    }

    void order(ByteOrder byteOrder) {
      this.data.order(byteOrder);
    }

    int length() {
      return data.remaining();
    }

    int getInt32(int offset) {
      return isAvailable(offset, 4) ? data.getInt(offset) : -1;
    }

    short getInt16(int offset) {
      return isAvailable(offset, 2) ? data.getShort(offset) : -1;
    }

    private boolean isAvailable(int offset, int byteSize) {
      if (offset < 0){
        return false;
      }
      return data.remaining() - offset >= byteSize;
    }
  }

  private interface Reader {

    /**
     * Reads and returns a 8-bit unsigned integer.
     *
     * <p>Throws an {@link EndOfFileException} if an EOF is reached.
     */
    short getUInt8() throws IOException;

    /**
     * Reads and returns a 16-bit unsigned integer.
     *
     * <p>Throws an {@link EndOfFileException} if an EOF is reached.
     */
    int getUInt16() throws IOException;

    /**
     * Reads and returns a byte array.
     *
     * <p>Throws an {@link EndOfFileException} if an EOF is reached before anything was read.
     */
    int read(byte[] buffer, int byteCount) throws IOException;

    long skip(long total) throws IOException;

    // TODO(timurrrr): Stop inheriting from IOException, and make sure all attempts to read from
    //   a Reader correctly handle EOFs.
    final class EndOfFileException extends IOException {
      private static final long serialVersionUID = 1L;

      EndOfFileException() {
        super("Unexpectedly reached end of a file");
      }
    }
  }

  private static final class ByteBufferReader implements Reader {

    private final ByteBuffer byteBuffer;

    ByteBufferReader(ByteBuffer byteBuffer) {
      this.byteBuffer = byteBuffer;
      byteBuffer.order(ByteOrder.BIG_ENDIAN);
    }

    @Override
    public short getUInt8() throws EndOfFileException {
      if (byteBuffer.remaining() < 1) {
        throw new EndOfFileException();
      }
      return (short) (byteBuffer.get() & 0xFF);
    }

    @Override
    public int getUInt16() throws EndOfFileException {
      return ((int) getUInt8() << 8) | getUInt8();
    }

    @Override
    public int read(byte[] buffer, int byteCount) {
      int toRead = Math.min(byteCount, byteBuffer.remaining());
      if (toRead == 0) {
        return -1;
      }
      byteBuffer.get(buffer, 0 /*dstOffset*/, toRead);
      return toRead;
    }

    @Override
    public long skip(long total) {
      int toSkip = (int) Math.min(byteBuffer.remaining(), total);
      byteBuffer.position(byteBuffer.position() + toSkip);
      return toSkip;
    }
  }

  private static final class StreamReader implements Reader {
    private final InputStream is;

    // Motorola / big endian byte order.
    StreamReader(InputStream is) {
      this.is = is;
    }

    @Override
    public short getUInt8() throws IOException {
      int readResult = is.read();
      if (readResult == -1) {
        throw new EndOfFileException();
      }

      return (short) readResult;
    }

    @Override
    public int getUInt16() throws IOException {
      return ((int) getUInt8() << 8) | getUInt8();
    }

    @Override
    public int read(byte[] buffer, int byteCount) throws IOException {
      int numBytesRead = 0;
      int lastReadResult = 0;
      while (numBytesRead < byteCount
          && (lastReadResult = is.read(buffer, numBytesRead, byteCount - numBytesRead)) != -1) {
        numBytesRead += lastReadResult;
      }

      if (numBytesRead == 0 && lastReadResult == -1) {
        throw new EndOfFileException();
      }

      return numBytesRead;
    }

    @Override
    public long skip(long total) throws IOException {
      if (total < 0) {
        return 0;
      }

      long toSkip = total;
      while (toSkip > 0) {
        long skipped = is.skip(toSkip);
        if (skipped > 0) {
          toSkip -= skipped;
        } else {
          // Skip has no specific contract as to what happens when you reach the end of
          // the stream. To differentiate between temporarily not having more data and
          // having finished the stream, we read a single byte when we fail to skip any
          // amount of data.
          int testEofByte = is.read();
          if (testEofByte == -1) {
            break;
          } else {
            toSkip--;
          }
        }
      }
      return total - toSkip;
    }
  }
}
