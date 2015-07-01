package org.apache.lucene.codecs.perfield;

/*
 *
 * Copyright(c) 2015, Samsung Electronics Co., Ltd.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the <organization> nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.
    
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */ 

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader; // javadocs
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.lucene.codecs.FieldsConsumer;
import org.apache.lucene.codecs.FieldsProducer;
import org.apache.lucene.codecs.PostingsFormat;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.Fields;
import org.apache.lucene.index.FilterLeafReader.FilterFields;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Terms;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.Accountables;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.RamUsageEstimator;

/**
 * Enables per field postings support.
 * <p>
 * Note, when extending this class, the name ({@link #getName}) is 
 * written into the index. In order for the field to be read, the
 * name must resolve to your implementation via {@link #forName(String)}.
 * This method uses Java's 
 * {@link ServiceLoader Service Provider Interface} to resolve format names.
 * <p>
 * Files written by each posting format have an additional suffix containing the 
 * format name. For example, in a per-field configuration instead of <tt>_1.prx</tt> 
 * filenames would look like <tt>_1_Lucene40_0.prx</tt>.
 * @see ServiceLoader
 * @lucene.experimental
 */

public abstract class PerFieldPostingsFormat extends PostingsFormat {
  /** Name of this {@link PostingsFormat}. */
  public static final String PER_FIELD_NAME = "PerField40";

  /** {@link FieldInfo} attribute name used to store the
   *  format name for each field. */
  public static final String PER_FIELD_FORMAT_KEY = PerFieldPostingsFormat.class.getSimpleName() + ".format";

  /** {@link FieldInfo} attribute name used to store the
   *  segment suffix name for each field. */
  public static final String PER_FIELD_SUFFIX_KEY = PerFieldPostingsFormat.class.getSimpleName() + ".suffix";

  /** Sole constructor. */
  public PerFieldPostingsFormat() {
    super(PER_FIELD_NAME);
  }

  /** Group of fields written by one PostingsFormat */
  static class FieldsGroup {
    final Set<String> fields = new TreeSet<>();
    int suffix;

    /** Custom SegmentWriteState for this group of fields,
     *  with the segmentSuffix uniqueified for this
     *  PostingsFormat */
    SegmentWriteState state;
  };

  static String getSuffix(String formatName, String suffix) {
    return formatName + "_" + suffix;
  }

  static String getFullSegmentSuffix(String fieldName, String outerSegmentSuffix, String segmentSuffix) {
    if (outerSegmentSuffix.length() == 0) {
      return segmentSuffix;
    } else {
      // TODO: support embedding; I think it should work but
      // we need a test confirm to confirm
      // return outerSegmentSuffix + "_" + segmentSuffix;
      throw new IllegalStateException("cannot embed PerFieldPostingsFormat inside itself (field \"" + fieldName + "\" returned PerFieldPostingsFormat)");
    }
  }
  
  private class FieldsWriter extends FieldsConsumer {
    final SegmentWriteState writeState;
    final List<Closeable> toClose = new ArrayList<Closeable>();

    public FieldsWriter(SegmentWriteState writeState) {
      this.writeState = writeState;
    }

    @Override
    public void write(Fields fields) throws IOException {

      // Maps a PostingsFormat instance to the suffix it
      // should use
      Map<PostingsFormat,FieldsGroup> formatToGroups = new HashMap<>();

      // Holds last suffix of each PostingFormat name
      Map<String,Integer> suffixes = new HashMap<>();

      // First pass: assign field -> PostingsFormat
      for(String field : fields) {
        FieldInfo fieldInfo = writeState.fieldInfos.fieldInfo(field);

        final PostingsFormat format = getPostingsFormatForField(field);
  
        if (format == null) {
          throw new IllegalStateException("invalid null PostingsFormat for field=\"" + field + "\"");
        }
        String formatName = format.getName();
      
        FieldsGroup group = formatToGroups.get(format);
        if (group == null) {
          // First time we are seeing this format; create a
          // new instance

          // bump the suffix
          Integer suffix = suffixes.get(formatName);
          if (suffix == null) {
            suffix = 0;
          } else {
            suffix = suffix + 1;
          }
          suffixes.put(formatName, suffix);

          String segmentSuffix = getFullSegmentSuffix(field,
                                                      writeState.segmentSuffix,
                                                      getSuffix(formatName, Integer.toString(suffix)));
          group = new FieldsGroup();
          group.state = new SegmentWriteState(writeState, segmentSuffix);
          group.suffix = suffix;
          formatToGroups.put(format, group);
        } else {
          // we've already seen this format, so just grab its suffix
          if (!suffixes.containsKey(formatName)) {
            throw new IllegalStateException("no suffix for format name: " + formatName + ", expected: " + group.suffix);
          }
        }

        group.fields.add(field);

        String previousValue = fieldInfo.putAttribute(PER_FIELD_FORMAT_KEY, formatName);
        if (previousValue != null) {
          throw new IllegalStateException("found existing value for " + PER_FIELD_FORMAT_KEY + 
                                          ", field=" + fieldInfo.name + ", old=" + previousValue + ", new=" + formatName);
        }

        previousValue = fieldInfo.putAttribute(PER_FIELD_SUFFIX_KEY, Integer.toString(group.suffix));
        if (previousValue != null) {
          throw new IllegalStateException("found existing value for " + PER_FIELD_SUFFIX_KEY + 
                                          ", field=" + fieldInfo.name + ", old=" + previousValue + ", new=" + group.suffix);
        }
      }

      // Second pass: write postings
      boolean success = false;
      try {
        for(Map.Entry<PostingsFormat,FieldsGroup> ent : formatToGroups.entrySet()) {
          PostingsFormat format = ent.getKey();
          final FieldsGroup group = ent.getValue();

          // Exposes only the fields from this group:
          Fields maskedFields = new FilterFields(fields) {
              @Override
              public Iterator<String> iterator() {
                return group.fields.iterator();
              }
            };

          FieldsConsumer consumer = format.fieldsConsumer(group.state);
          toClose.add(consumer);
          consumer.write(maskedFields);
        }
        success = true;
      } finally {
        if (success == false) {
          IOUtils.closeWhileHandlingException(toClose);
        }
      }
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(toClose);
    }
  }

  private static class FieldsReader extends FieldsProducer {

    private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(FieldsReader.class);

    private final Map<String,FieldsProducer> fields = new TreeMap<>();
    private final Map<String,FieldsProducer> formats = new HashMap<>();
    
    // clone for merge
    FieldsReader(FieldsReader other) throws IOException {
      Map<FieldsProducer,FieldsProducer> oldToNew = new IdentityHashMap<>();
      // First clone all formats
      for(Map.Entry<String,FieldsProducer> ent : other.formats.entrySet()) {
        FieldsProducer values = ent.getValue().getMergeInstance();
        formats.put(ent.getKey(), values);
        oldToNew.put(ent.getValue(), values);
      }

      // Then rebuild fields:
      for(Map.Entry<String,FieldsProducer> ent : other.fields.entrySet()) {
        FieldsProducer producer = oldToNew.get(ent.getValue());
        assert producer != null;
        fields.put(ent.getKey(), producer);
      }
    }

    public FieldsReader(final SegmentReadState readState) throws IOException {

      // Read _X.per and init each format:
      boolean success = false;
      try {
        // Read field name -> format name
        for (FieldInfo fi : readState.fieldInfos) {
          if (fi.getIndexOptions() != IndexOptions.NONE) {
            final String fieldName = fi.name;
            final String formatName = fi.getAttribute(PER_FIELD_FORMAT_KEY);
            if (formatName != null) {
              // null formatName means the field is in fieldInfos, but has no postings!
              final String suffix = fi.getAttribute(PER_FIELD_SUFFIX_KEY);
              if (suffix == null) {
                throw new IllegalStateException("missing attribute: " + PER_FIELD_SUFFIX_KEY + " for field: " + fieldName);
              }
              PostingsFormat format = PostingsFormat.forName(formatName);
              String segmentSuffix = getSuffix(formatName, suffix);
              if (!formats.containsKey(segmentSuffix)) {
                formats.put(segmentSuffix, format.fieldsProducer(new SegmentReadState(readState, segmentSuffix)));
              }
              fields.put(fieldName, formats.get(segmentSuffix));
            }
          }
        }
        success = true;
      } finally {
        if (!success) {
          IOUtils.closeWhileHandlingException(formats.values());
        }
      }
    }

    @Override
    public Iterator<String> iterator() {
      return Collections.unmodifiableSet(fields.keySet()).iterator();
    }

    @Override
    public Terms terms(String field) throws IOException {
      FieldsProducer fieldsProducer = fields.get(field);
      return fieldsProducer == null ? null : fieldsProducer.terms(field);
    }
    
    @Override
    public int size() {
      return fields.size();
    }

    @Override
    public void close() throws IOException {
      IOUtils.close(formats.values());
    }

    @Override
    public long ramBytesUsed() {
      long ramBytesUsed = BASE_RAM_BYTES_USED;
      ramBytesUsed += fields.size() * 2L * RamUsageEstimator.NUM_BYTES_OBJECT_REF;
      ramBytesUsed += formats.size() * 2L * RamUsageEstimator.NUM_BYTES_OBJECT_REF;
      for(Map.Entry<String,FieldsProducer> entry: formats.entrySet()) {
        ramBytesUsed += entry.getValue().ramBytesUsed();
      }
      return ramBytesUsed;
    }
    
    @Override
    public Collection<Accountable> getChildResources() {
      return Accountables.namedAccountables("format", formats);
    }

    @Override
    public void checkIntegrity() throws IOException {
      for (FieldsProducer producer : formats.values()) {
        producer.checkIntegrity();
      }
    }

    @Override
    public FieldsProducer getMergeInstance() throws IOException {
      return new FieldsReader(this);
    }

    @Override
    public String toString() {
      return "PerFieldPostings(formats=" + formats.size() + ")";
    }
  }

  @Override
  public final FieldsConsumer fieldsConsumer(SegmentWriteState state)
      throws IOException {
    return new FieldsWriter(state);
  }

  @Override
  public final FieldsProducer fieldsProducer(SegmentReadState state)
      throws IOException {
    return new FieldsReader(state);
  }

  /** 
   * Returns the postings format that should be used for writing 
   * new segments of <code>field</code>.
   * <p>
   * The field to format mapping is written to the index, so
   * this method is only invoked when writing, not when reading. */
  public abstract PostingsFormat getPostingsFormatForField(String field);
}