package completely;

import completely.data.Indexable;
import completely.data.ScoredObject;
import completely.text.analyze.Analyzer;
import completely.text.index.Index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nullable;

import static completely.common.Precondition.checkPointer;

/**
 * Facade for indexing and searching {@link Indexable} elements.
 */
public final class AutocompleteEngine<T extends Indexable> {
    private final Analyzer analyzer;
    private final Comparator<ScoredObject<T>> comparator;
    private final IndexAdapter<T> index;
    private final Lock read;
    private final Lock write;
    
    private AutocompleteEngine(Builder<T> builder) {
        assert builder != null;
        this.analyzer = builder.analyzer;
        this.comparator = builder.comparator;
        this.index = builder.index;
        ReadWriteLock lock = new ReentrantReadWriteLock();
        this.read = lock.readLock();
        this.write = lock.writeLock();
    }
    
    /**
     * Indexes a single element.
     *
     * @throws NullPointerException
     *             if {@code element} is null;
     */
    public boolean add(T element) {
        boolean result = false;
        checkPointer(element != null);
        write.lock();
        try {
            for (String field : element.getFields()) {
                for (String token : analyzer.apply(field)) {
                    if (index.put(token, element)) {
                        result |= true;
                    }
                }
            }
        } finally {
            write.unlock();
        }
        return result;
    }
    
    /**
     * Indexes a collection of elements.
     *
     * @throws NullPointerException
     *             if {@code elements} is null or contains a null element;
     */
    public boolean addAll(Collection<T> elements) {
        checkPointer(elements != null);
        boolean result = false;
        for (T element : elements) {
            result |= add(element);
        }
        return result;
    }
    
    /**
     * Remove all elements
     */
    public void removeAll() {
        try {
            write.lock();
            index.clear();
        } finally {
            write.unlock();
        }
    }
    
    /**
     * Returns a {@link List} of all elements that match a query, sorted
     * according to the default comparator.
     */
    public List<T> search(String query) {
        checkPointer(query != null);
        read.lock();
        try {
            Aggregator<T> aggregator = new Aggregator<>(comparator);
            Iterator<String> tokens = analyzer.apply(query).iterator();
            if (tokens.hasNext()) {
                aggregator.addAll(index.get(tokens.next()));
            }
            while (tokens.hasNext()) {
                if (aggregator.isEmpty()) {
                    break;
                }
                aggregator.retainAll(index.get(tokens.next()));
            }
            return aggregator.values();
        } finally {
            read.unlock();
        }
    }
    
    /**
     * Returns a {@link List} of the top elements that match a query, sorted
     * according to the default comparator.
     */
    public List<T> search(String query, int limit) {
        List<T> result = search(query);
        if (result.size() > limit) {
            return result.subList(0, limit);
        }
        return result;
    }
    
    /**
     * Builder for constructing {@link AutocompleteEngine} instances.
     */
    public static class Builder<T extends Indexable> {
        private Analyzer analyzer;
        private Comparator<ScoredObject<T>> comparator;
        private IndexAdapter<T> index;
        
        /**
         * Constructs a new {@link AutocompleteEngine.Builder}.
         */
        public Builder() {
            this.analyzer = new Analyzer() {
                @Override
                public Collection<String> apply(Collection<String> input) {
                    return new ArrayList<>(input);
                }
            };
        }
        
        /**
         * Set the analyzer.
         */
        public Builder<T> setAnalyzer(Analyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }
        
        /**
         * Set the comparator.
         */
        public Builder<T> setComparator(@Nullable Comparator<ScoredObject<T>> comparator) {
            this.comparator = comparator;
            return this;
        }
        
        /**
         * Set the index.
         *
         * @throws NullPointerException
         *             if {@code index} is null;
         */
        public Builder<T> setIndex(final Index<T> index) {
            checkPointer(index != null);
            return setIndex(new IndexAdapter<T>() {
                @Override
                public Collection<ScoredObject<T>> get(String token) {
                    List<ScoredObject<T>> result = new LinkedList<>();
                    for (T element : index.getAll(token)) {
                        result.add(new ScoredObject<>(element, 0));
                    }
                    return result;
                }
                
                @Override
                public boolean put(String token, T value) {
                    return index.put(token, value);
                }
                
                @Override
                public void clear() {
                    index.clear();
                }
            });
        }
        
        /**
         * Set the index.
         */
        public Builder<T> setIndex(IndexAdapter<T> index) {
            this.index = index;
            return this;
        }
        
        /**
         * Returns a new {@link AutocompleteEngine} parameterized according to
         * the builder.
         *
         * @throws NullPointerException
         *             if {@code analyzer} or {@code index} are null;
         */
        public AutocompleteEngine<T> build() {
            checkPointer(analyzer != null);
            checkPointer(index != null);
            return new AutocompleteEngine<>(this);
        }
    }
}
