package com.google.common.collect;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;

public final class ImmutableSet<E> extends AbstractSet<E> {
    private final LinkedHashSet<E> items;

    private ImmutableSet(Collection<E> source) {
        items = new LinkedHashSet<E>(source);
    }

    public static <E> ImmutableSet<E> of() {
        return new ImmutableSet<E>(Arrays.<E>asList());
    }

    public static <E> ImmutableSet<E> of(E one) {
        return new ImmutableSet<E>(Arrays.asList(one));
    }

    public static <E> ImmutableSet<E> of(E one, E two) {
        return new ImmutableSet<E>(Arrays.asList(one, two));
    }

    public static <E> ImmutableSet<E> of(E one, E two, E three) {
        return new ImmutableSet<E>(Arrays.asList(one, two, three));
    }

    public static <E> ImmutableSet<E> of(E one, E two, E three, E four) {
        return new ImmutableSet<E>(Arrays.asList(one, two, three, four));
    }

    public static <E> ImmutableSet<E> of(E one, E two, E three, E four, E five) {
        return new ImmutableSet<E>(Arrays.asList(one, two, three, four, five));
    }

    @Override
    public Iterator<E> iterator() {
        return items.iterator();
    }

    @Override
    public int size() {
        return items.size();
    }
}
