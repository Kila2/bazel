// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.testutils;

import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.getTypeName;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.getClassInfo;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Fingerprinter.computeFingerprints;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.ClosedClassInfo;
import com.google.devtools.build.lib.skyframe.serialization.testutils.FieldInfoCache.PrimitiveInfo;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A utility for creating high fidelity string dumps of arbitrary objects.
 *
 * <p>Uses reflection to perform depth-first traversal of arbitrary objects and formats them as an
 * indented, multiline string.
 *
 * <p>This class exists mainly to help test and debug serialization. Consequently, it skips {@code
 * transient} fields. It also performs reference-based memoization to handle cyclic structures or
 * structures that would have an exponential path structure, for example, {@code NestedSets}.
 *
 * <p>This class also supports value-based deduplication when calling {@link
 * #dumpStructureWithEquivalenceReduction}. Instead of using (only) using references for
 * deduplication, uses object fingerprints computed by {@link Fingerprinter} for deduplication.
 */
public final class Dumper implements GraphDataCollector<Dumper.TextSink> {
  private static final HexFormat HEX_FORMAT = HexFormat.of().withUpperCase();

  /**
   * Fingerprints for references.
   *
   * <p>Even if this is present, not all references will have fingerprints. In particular, anything
   * where {@link Dumper#shouldInline} is true or inner elements of unresolvable object cycles will
   * not have fingerprints.
   */
  @Nullable // optional behavior
  private final IdentityHashMap<Object, String> fingerprints;

  /**
   * Stores an identifier for every object traversed.
   *
   * <p>When an object is encountered again, it is represented with just its type and previous
   * identifier instead of being fully expanded.
   */
  private final IdentityHashMap<Object, Integer> referenceIds = new IdentityHashMap<>();

  /**
   * When true, emits a fingerprint directly for objects found in {@link #fingerprints}.
   *
   * <p>When false, the fingerprint is used only as a reference for deduplication. The object having
   * the fingerprint is still output as text. If another object with the same fingerprint is
   * encountered, a normal backreference is emitted.
   */
  private final boolean emitFingerprints;

  /**
   * Formats an arbitrary object into a string.
   *
   * <p>The format is verbose and suitable for tests and debugging.
   *
   * @return a multiline String representation of {@code obj} without a trailing newline
   */
  public static String dumpStructure(@Nullable ObjectCodecRegistry registry, Object obj) {
    return dumpStructure(registry, /* fingerprints= */ null, obj);
  }

  public static String dumpStructure(Object obj) {
    return dumpStructure(/* registry= */ null, obj);
  }

  private static String dumpStructure(
      @Nullable ObjectCodecRegistry registry,
      @Nullable IdentityHashMap<Object, String> fingerprints,
      Object obj) {
    var out = new StringBuilder();
    var sink = new TextSink(out);
    new GraphTraverser<>(registry, new Dumper(fingerprints, /* emitFingerprints= */ false))
        .traverseObject(/* label= */ null, obj, sink);
    return out.toString();
  }

  /**
   * Formats an arbitrary object into a string.
   *
   * <p>Similar to {@link #dumpStructure} but applies fingerprint-based deduplication.
   */
  public static String dumpStructureWithEquivalenceReduction(
      @Nullable ObjectCodecRegistry registry, Object obj) {
    return dumpStructure(registry, computeFingerprints(registry, obj), obj);
  }

  public static String dumpStructureWithEquivalenceReduction(Object obj) {
    return dumpStructureWithEquivalenceReduction(/* registry= */ null, obj);
  }

  /**
   * Traverses the object graph starting from {@code obj} and returns the traversal order.
   *
   * @param fingerprints (if provided) objects contained are emitted as their fingerprints without
   *     traversing them.
   * @param out accepts the text dump of the traversal (with fingerprint values for objects
   *     contained in {@code fingerprints}).
   * @return a map from object to its traversal order, excluding elements in {@code fingerprints}
   */
  static IdentityHashMap<Object, Integer> computeVisitOrder(
      @Nullable ObjectCodecRegistry registry,
      @Nullable IdentityHashMap<Object, String> fingerprints,
      Object obj,
      StringBuilder out) {
    var collector = new Dumper(fingerprints, /* emitFingerprints= */ true);
    var sink = new TextSink(out);
    new GraphTraverser<>(registry, collector).traverseObject(/* label= */ null, obj, sink);
    return collector.referenceIds;
  }

  static final class TextSink implements GraphDataCollector.Sink {
    private static final int SPACES_PER_INDENT = 2;

    private final StringBuilder out;
    private int indent;
    boolean isFirst = true;

    TextSink(StringBuilder out) {
      this.out = out;
    }

    @Override
    public void completeAggregate() {
      deindent();
      emitNewlineAndIndent();
      out.append("]");
    }

    private void output(@Nullable String label, String text) {
      emitNewlineAndIndent();
      if (label != null) {
        out.append(label);
      }
      out.append(text);
    }

    private void indent() {
      indent += SPACES_PER_INDENT;
    }

    private void deindent() {
      indent -= SPACES_PER_INDENT;
    }

    private void emitNewlineAndIndent() {
      if (isFirst) {
        isFirst = false; // suppresses the first newline
        return;
      }
      out.append("\n").append(" ".repeat(indent));
    }
  }

  private Dumper(@Nullable IdentityHashMap<Object, String> fingerprints, boolean emitFingerprints) {
    this.fingerprints = fingerprints;
    this.emitFingerprints = emitFingerprints;
  }

  @Override
  public void outputNull(@Nullable String label, TextSink sink) {
    sink.output(label, "null");
  }

  @Override
  public void outputSerializationConstant(
      @Nullable String label, Class<?> type, int tag, TextSink sink) {
    sink.output(label, getTypeName(type) + "[SERIALIZATION_CONSTANT:" + tag + ']');
  }

  @Override
  public void outputWeakReference(@Nullable String label, TextSink sink) {
    sink.output(label, WeakReference.class.getCanonicalName());
  }

  @Override
  public void outputInlineObject(@Nullable String label, Class<?> type, Object obj, TextSink sink) {
    sink.output(label, obj.toString());
  }

  @Override
  public void outputPrimitive(PrimitiveInfo info, Object parent, TextSink sink) {
    sink.output(info.name() + '=', info.getText(parent));
  }

  @Override
  @Nullable
  public Descriptor checkCache(@Nullable String label, Class<?> type, Object obj, TextSink sink) {
    int nextId = referenceIds.size();
    String fingerprint;
    if (fingerprints != null && ((fingerprint = fingerprints.get(obj)) != null)) {
      if (emitFingerprints) {
        sink.output(label, getTypeName(type) + '[' + fingerprint + ']');
        return null;
      }
      // There's a fingerprint for `obj`. Uses it to lookup a reference ID.
      Integer previousId = referenceIds.putIfAbsent(fingerprint, nextId);
      if (previousId != null) {
        // An object having this fingerprint has been observed previously. Outputs only a
        // backreference.
        sink.output(label, getDescriptor(type, previousId).toString());
        return null;
      }
    } else {
      // No fingerprint is available. Deduplicates by object reference.
      Integer previousId = referenceIds.putIfAbsent(obj, nextId);
      if (previousId != null) {
        // This instance has been observed previously. Outputs only a backreference.
        sink.output(label, getDescriptor(type, previousId).toString());
        return null;
      }
    }
    return getDescriptor(type, nextId);
  }

  @Override
  public void outputByteArray(
      @Nullable String label, Descriptor descriptor, byte[] bytes, TextSink sink) {
    sink.output(label, descriptor + " [" + HEX_FORMAT.formatHex(bytes) + ']');
  }

  @Override
  public void outputInlineArray(
      @Nullable String label, Descriptor descriptor, Object arr, TextSink sink) {
    var builder = new StringBuilder(descriptor.toString()).append(" [");
    int length = Array.getLength(arr);
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        builder.append(", ");
      }
      builder.append(Array.get(arr, i));
    }
    builder.append(']');
    sink.output(label, builder.toString());
  }

  @Override
  public void outputEmptyAggregate(
      @Nullable String label, Descriptor descriptor, Object unused, TextSink sink) {
    sink.output(label, descriptor + " []");
  }

  @Override
  @SuppressWarnings("CanIgnoreReturnValueSuggester")
  public TextSink initAggregate(
      @Nullable String label, Descriptor descriptor, Object unused, TextSink sink) {
    sink.output(label, descriptor + " [");
    sink.indent();
    return sink;
  }

  static String getTypeName(Class<?> type) {
    String name = type.getCanonicalName();
    if (name == null) {
      // According to the documentation for `Class.getCanonicalName`, not all classes have one.
      // Falls back on the name in such cases. (It's unclear if this code is reachable because
      // synthetic types are inlined).
      name = type.getName();
    }
    return name;
  }

  static boolean shouldInline(Class<?> type) {
    if (type.isArray()) {
      return false;
    }
    if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) {
      // These types have custom handling and do not depend on reflective class information.
      return false;
    }
    return type.isPrimitive()
        || DIRECT_INLINE_TYPES.contains(type)
        || type.isSynthetic()
        // Reflectively inaccessible classes will be represented directly using their string
        // representations as there's nothing else we can do with them.
        //
        // TODO: b/331765692 - this might cause a loss of fidelity. Consider including a hash of
        // the serialized representation in such cases.
        || getClassInfo(type) instanceof ClosedClassInfo;
  }

  private static final ImmutableSet<Class<?>> WRAPPER_TYPES =
      ImmutableSet.of(
          Byte.class,
          Short.class,
          Integer.class,
          Long.class,
          Float.class,
          Double.class,
          Boolean.class,
          Character.class);

  private static final ImmutableSet<Class<?>> DIRECT_INLINE_TYPES =
      ImmutableSet.<Class<?>>builder()
          .addAll(WRAPPER_TYPES)
          // Treats Strings as values for readability of the output. It might be good to make this
          // configurable later on.
          .add(String.class)
          // The string representation of a Class is sufficient to identify it.
          .add(Class.class)
          .build();

  private static Descriptor getDescriptor(Class<?> type, int id) {
    return new Descriptor(getTypeName(type), id);
  }
}
