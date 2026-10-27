package com.automaker.merge;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.InputSource;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.archive.block.ApkSignatureBlock;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.SpecBlock;
import com.reandroid.arsc.chunk.TypeBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class MergeXapk {
    private static final Set<String> APPLICATION_TAGS = Set.of(
        "activity",
        "activity-alias",
        "service",
        "receiver",
        "provider",
        "uses-library",
        "meta-data",
        "property"
    );
    private static final Set<String> ROOT_TAGS = Set.of(
        "uses-permission",
        "permission",
        "permission-tree",
        "permission-group",
        "uses-feature"
    );
    private static final String[] META_NAMES = {
        "com.android.vending.splits.required",
        "com.android.vending.splits",
        "com.android.stamp.source",
        "com.android.stamp.type",
        "com.android.vending.derived.apk.id",
    };

    private MergeXapk() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: MergeXapk <input.xapk> <output.apk>");
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        File parent = output.getParentFile();
        if (parent != null) parent.mkdirs();
        File modules = new File(parent, "modules");
        deleteTree(modules);
        modules.mkdirs();
        progress(0.02);
        extractApks(input, modules);
        progress(0.16);
        try (ApkBundle bundle = new ApkBundle()) {
            Thread loading = pulse(0.16, 0.55);
            try {
                bundle.loadApkDirectory(modules);
            } finally {
                loading.interrupt();
            }
            progress(0.55);
            try (ApkModule merged = mergeModules(bundle)) {
                progress(0.90);
                mergeFeatureManifests(bundle, merged);
                stripSplitMetadata(merged);
                progress(0.93);
                repairResourceTable(merged);
                progress(0.95);
                merged.setApkSignatureBlock(null);
                if (output.exists() && !output.delete()) {
                    throw new IllegalStateException("The merged APK could not be replaced.");
                }
                progress(0.96);
                merged.writeApk(output);
                progress(0.99);
                stripShortOverlayables(output);
                progress(1);
            }
        } finally {
            deleteTree(modules);
        }
    }

    private static void progress(double ratio) {
        System.out.printf("PROGRESS %.4f%n", Math.max(0, Math.min(1, ratio)));
        System.out.flush();
    }

    private static void extractApks(File xapk, File dest) throws Exception {
        List<ZipEntry> apks = new ArrayList<>();
        long total = 0;
        try (ZipFile zip = new ZipFile(xapk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory() || !name.toLowerCase().endsWith(".apk")) continue;
                apks.add(entry);
                total += Math.max(0, entry.getSize());
            }
            if (apks.isEmpty()) throw new IllegalArgumentException("The XAPK does not contain an APK.");
            long done = 0;
            byte[] buffer = new byte[256 * 1024];
            for (int index = 0; index < apks.size(); index++) {
                ZipEntry entry = apks.get(index);
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../") || name.contains("\0")) {
                    throw new IllegalArgumentException("The XAPK contains an unsafe path.");
                }
                String base = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
                File target = new File(dest, index + "_" + base);
                if (!target.getCanonicalPath().startsWith(dest.getCanonicalPath() + File.separator)) {
                    throw new IllegalArgumentException("The XAPK contains an unsafe path.");
                }
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
                    while (true) {
                        int read = input.read(buffer);
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                        done += read;
                        if (total > 0) progress(0.02 + (0.14 * done / total));
                    }
                }
            }
        }
    }

    private static ApkModule mergeModules(ApkBundle bundle) throws IOException {
        List<ApkModule> modules = bundle.getApkModuleList();
        if (modules.isEmpty()) throw new java.io.FileNotFoundException("Nothing to merge, empty modules");
        ApkModule base = bundle.getBaseModule();
        if (base == null) {
            ApkModule largest = null;
            int size = -1;
            for (ApkModule module : modules) {
                int chunk = 0;
                if (module.hasTableBlock() && module.getTableBlock() != null && module.getTableBlock().getHeaderBlock() != null) {
                    chunk = module.getTableBlock().getHeaderBlock().getChunkSize();
                }
                if (largest == null || chunk > size) {
                    largest = module;
                    size = chunk;
                }
            }
            base = largest != null ? largest : modules.get(0);
        }
        List<ApkModule> ordered = new ArrayList<>();
        ordered.add(base);
        for (ApkModule module : modules) {
            if (module != base) ordered.add(module);
        }
        long[] weights = new long[ordered.size()];
        long total = 0;
        for (int index = 0; index < ordered.size(); index++) {
            weights[index] = moduleWeight(ordered.get(index));
            total += weights[index];
        }
        if (total < 1) total = 1;
        ApkModule merged = new ApkModule("merged", new ZipEntryMap());
        merged.setLoadDefaultFramework(false);
        ApkSignatureBlock signature = base.getApkSignatureBlock();
        long done = 0;
        for (int index = 0; index < ordered.size(); index++) {
            ApkModule module = ordered.get(index);
            double start = 0.55 + (0.33 * ((double) done / total));
            double end = 0.55 + (0.33 * ((double) (done + weights[index]) / total));
            Thread ticker = pulse(start, end);
            try {
                merged.merge(module, false);
            } finally {
                ticker.interrupt();
            }
            if (module != base && signature == null) signature = module.getApkSignatureBlock();
            done += weights[index];
            progress(end);
        }
        merged.setApkSignatureBlock(signature);
        if (merged.hasTableBlock() && merged.getTableBlock() != null) {
            merged.getTableBlock().sortPackages();
            merged.getTableBlock().refresh();
        }
        merged.getZipEntryMap().autoSortApkFiles();
        return merged;
    }

    private static long moduleWeight(ApkModule module) {
        long bytes = 0;
        if (module.hasTableBlock() && module.getTableBlock() != null && module.getTableBlock().getHeaderBlock() != null) {
            bytes += Math.max(0, module.getTableBlock().getHeaderBlock().getChunkSize());
        }
        InputSource[] sources = module.getInputSources();
        if (sources != null) bytes += sources.length;
        return Math.max(1L, bytes);
    }

    private static Thread pulse(double from, double until) {
        Thread ticker = new Thread(() -> {
            long started = System.nanoTime();
            double width = Math.max(0.001, until - from);
            double points = Math.max(1.0, width * 62.0);
            double tauSec = Math.min(240.0, Math.max(6.0, points / 0.22));
            while (!Thread.currentThread().isInterrupted()) {
                double elapsedSec = (System.nanoTime() - started) / 1_000_000_000.0;
                double eased = Math.min(0.98, 1.0 - Math.exp(-elapsedSec / tauSec));
                progress(from + (width * eased));
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException stopped) {
                    break;
                }
            }
        });
        ticker.setDaemon(true);
        ticker.start();
        return ticker;
    }

    private static void mergeFeatureManifests(ApkBundle bundle, ApkModule merged) throws IOException {
        AndroidManifestBlock target = merged.getAndroidManifest();
        if (target == null || target.getManifestElement() == null || target.getApplicationElement() == null) return;
        merged.refreshManifest();
        for (ApkModule module : bundle.getModules()) {
            module.refreshManifest();
            AndroidManifestBlock source = module.getAndroidManifest();
            if (source == null || source.getManifestElement() == null) continue;
            copyNamedChildren(target.getManifestElement(), source.getManifestElement(), ROOT_TAGS);
            if (source.getApplicationElement() != null) {
                copyNamedChildren(target.getApplicationElement(), source.getApplicationElement(), APPLICATION_TAGS);
            }
        }
        target.refresh();
    }

    private static void copyNamedChildren(ResXmlElement destination, ResXmlElement source, Set<String> tags) throws IOException {
        Iterator<?> iterator = source.getElements();
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (!(next instanceof ResXmlElement element)) continue;
            if (!tags.contains(element.getName()) || isDistributionElement(element)) continue;
            String name = AndroidManifestBlock.getAndroidNameValue(element);
            if (name == null || hasNamedChild(destination, element.getName(), name)) continue;
            ResXmlElement copy = destination.newElement(element.getName());
            try {
                copyElement(copy, element);
            } catch (IOException error) {
                ResXmlElement owner = copy.getParentElement();
                if (owner != null) owner.remove(copy);
            }
        }
    }

    private static void copyElement(ResXmlElement destination, ResXmlElement source) throws IOException {
        destination.setName(source.getName());
        if (source.getUri() != null || source.getPrefix() != null) {
            destination.setNamespace(source.getUri(), source.getPrefix());
        }
        Iterator<ResXmlAttribute> attributes = source.getAttributes();
        while (attributes.hasNext()) {
            ResXmlAttribute attribute = attributes.next();
            copyAttribute(destination, attribute);
        }
        Iterator<?> children = source.getElements();
        while (children.hasNext()) {
            Object next = children.next();
            if (!(next instanceof ResXmlElement child) || isDistributionElement(child)) continue;
            ResXmlElement copy = destination.newElement(child.getName());
            copyElement(copy, child);
        }
    }

    private static void copyAttribute(ResXmlElement destination, ResXmlAttribute attribute) throws IOException {
        String name = attribute.getName();
        if (name == null || name.isEmpty() || name.startsWith("r0x")) name = attribute.decodeName(false);
        if (name == null || name.isEmpty()) return;
        boolean stringValue = attribute.getValueType() == null || "STRING".equals(attribute.getValueType().name());
        String value = stringValue ? safeValue(attribute) : null;
        if (stringValue && (value == null || value.isEmpty())) return;
        ResXmlAttribute created = destination.newAttribute();
        created.setName(name, attribute.getNameId());
        if (attribute.getUri() != null) created.setNamespace(attribute.getUri(), attribute.getPrefix());
        try {
            if (attribute.getValueType() != null && "STRING".equals(attribute.getValueType().name())) {
                String text = attribute.getValueAsString();
                if (text == null) throw new IOException("The split manifest attribute has no value.");
                created.setValueAsString(text);
            } else if (attribute.getValueType() != null) {
                created.setValueType(attribute.getValueType());
                created.setData(attribute.getData());
            } else if (value != null) {
                created.setValueAsString(value);
            }
        } catch (RuntimeException error) {
            destination.removeAttribute(created);
            if ("name".equals(name)) throw new IOException("The split manifest attribute could not be copied.", error);
        }
    }

    private static String safeValue(ResXmlAttribute attribute) {
        try {
            String decoded = attribute.decodeValue(true);
            if (decoded != null && !decoded.isEmpty()) return decoded;
        } catch (RuntimeException ignored) {
        }
        try {
            return attribute.getValueAsString();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isDistributionElement(ResXmlElement element) {
        String uri = element.getUri();
        return uri != null && uri.contains("schemas.android.com/apk/distribution");
    }

    private static boolean hasNamedChild(ResXmlElement parent, String tag, String name) {
        Iterator<?> iterator = parent.getElements(tag);
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (next instanceof ResXmlElement element && name.equals(AndroidManifestBlock.getAndroidNameValue(element))) {
                return true;
            }
        }
        return false;
    }

    private static void stripSplitMetadata(ApkModule apk) {
        AndroidManifestBlock manifest = apk.getAndroidManifest();
        if (manifest == null || manifest.getManifestElement() == null) return;
        ResXmlElement root = manifest.getManifestElement();
        ResXmlElement application = manifest.getApplicationElement();
        removeSplitAttributes(root);
        if (application != null) removeSplitAttributes(application);
        removeNamedChildren(root, "uses-split");
        for (String name : META_NAMES) removeMeta(manifest, name);
        manifest.refresh();
    }

    private static void removeSplitAttributes(ResXmlElement element) {
        removeAttribute(element, AndroidManifest.ID_requiredSplitTypes, AndroidManifest.NAME_requiredSplitTypes);
        removeAttribute(element, AndroidManifest.ID_splitTypes, AndroidManifest.NAME_splitTypes);
        removeAttribute(element, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired);
        removeAttribute(element, AndroidManifest.ID_extractNativeLibs, AndroidManifest.NAME_extractNativeLibs);
        ResXmlAttribute split = element.searchAttributeByName(AndroidManifest.NAME_split);
        if (split != null) element.removeAttribute(split);
        ResXmlAttribute isolated = element.searchAttributeByName("isolatedSplits");
        if (isolated != null) element.removeAttribute(isolated);
    }

    private static void repairResourceTable(ApkModule apk) {
        if (!apk.hasTableBlock()) return;
        Iterator<PackageBlock> packages = apk.getTableBlock().iterator();
        while (packages.hasNext()) {
            PackageBlock pkg = packages.next();
            Iterator<SpecTypePair> pairs = pkg.getSpecTypePairs();
            while (pairs.hasNext()) {
                SpecTypePair pair = pairs.next();
                int needed = Math.max(pair.getHighestEntryCount(), pair.getHighestEntryId() + 1);
                if (needed < 1) continue;
                SpecBlock spec = pair.getSpecBlock();
                if (spec != null && spec.getEntryCount() < needed) spec.setEntryCount(needed);
                Iterator<TypeBlock> types = pair.getTypeBlocks();
                while (types.hasNext()) {
                    TypeBlock type = types.next();
                    if (type.getEntryArray().size() < needed) type.ensureEntriesCount(needed);
                }
            }
        }
        apk.getTableBlock().refreshFull();
    }

    private static void removeAttribute(ResXmlElement element, int id, String name) {
        ResXmlAttribute attribute = element.searchAttributeByResourceId(id);
        if (attribute == null) attribute = element.searchAttributeByName(name);
        if (attribute != null) element.removeAttribute(attribute);
    }

    private static void removeNamedChildren(ResXmlElement parent, String tag) {
        List<ResXmlElement> found = new ArrayList<>();
        var iterator = parent.getElements(tag);
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (next instanceof ResXmlElement element) found.add(element);
        }
        for (ResXmlElement element : found) {
            ResXmlElement owner = element.getParentElement();
            if (owner != null) owner.remove(element);
        }
    }

    private static void removeMeta(AndroidManifestBlock manifest, String name) {
        List<ResXmlElement> found = new ArrayList<>();
        var iterator = manifest.recursiveElements();
        while (iterator.hasNext()) {
            Object next = iterator.next();
            if (!(next instanceof ResXmlElement element)) continue;
            if (element.equalsName(AndroidManifest.TAG_meta_data)
                && name.equals(AndroidManifestBlock.getAndroidNameValue(element))) {
                found.add(element);
            }
        }
        for (ResXmlElement element : found) {
            ResXmlElement owner = element.getParentElement();
            if (owner != null) owner.remove(element);
        }
    }

    private static final int OVERLAYABLE = 0x0204;
    private static final int COMPLETE_OVERLAY_HEADER = 8 + 512 + 512;

    private static void stripShortOverlayables(File apk) throws IOException {
        File temp = new File(apk.getParentFile(), apk.getName() + ".overlay");
        boolean changed = false;
        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry resources = zip.getEntry("resources.arsc");
            if (resources == null) return;
            byte[] original = zip.getInputStream(resources).readAllBytes();
            byte[] stripped = removeShortOverlayables(original);
            if (Arrays.equals(original, stripped)) return;
            try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(temp))) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry item = entries.nextElement();
                    ZipEntry next = new ZipEntry(item.getName());
                    next.setTime(item.getTime());
                    if ("resources.arsc".equals(item.getName())) {
                        CRC32 crc = new CRC32();
                        crc.update(stripped);
                        next.setMethod(ZipEntry.STORED);
                        next.setSize(stripped.length);
                        next.setCompressedSize(stripped.length);
                        next.setCrc(crc.getValue());
                        out.putNextEntry(next);
                        out.write(stripped);
                    } else if (item.getMethod() == ZipEntry.STORED) {
                        next.setMethod(ZipEntry.STORED);
                        next.setSize(item.getSize());
                        next.setCompressedSize(item.getSize());
                        next.setCrc(item.getCrc());
                        out.putNextEntry(next);
                        try (InputStream input = zip.getInputStream(item)) {
                            input.transferTo(out);
                        }
                    } else {
                        out.putNextEntry(next);
                        try (InputStream input = zip.getInputStream(item)) {
                            input.transferTo(out);
                        }
                    }
                    out.closeEntry();
                }
            }
            changed = true;
        }
        if (!changed) return;
        if (!apk.delete() || !temp.renameTo(apk)) {
            throw new IOException("The rebuilt APK could not be replaced.");
        }
    }

    private static byte[] removeShortOverlayables(byte[] source) {
        if (source.length < 12) return source;
        byte[] data = source.clone();
        List<int[]> removals = new ArrayList<>();
        collectOverlayables(data, 0, data.length, false, removals);
        if (removals.isEmpty()) return source;
        int shrink = 0;
        for (int[] range : removals) shrink += range[1];
        shrinkPackages(data, 0, data.length, removals);
        setU32(data, 4, u32(data, 4) - shrink);
        removals.sort((left, right) -> Integer.compare(right[0], left[0]));
        byte[] current = data;
        for (int[] range : removals) {
            byte[] next = new byte[current.length - range[1]];
            System.arraycopy(current, 0, next, 0, range[0]);
            System.arraycopy(current, range[0] + range[1], next, range[0], current.length - range[0] - range[1]);
            current = next;
        }
        return current;
    }

    private static void collectOverlayables(byte[] data, int start, int end, boolean inPackage, List<int[]> removals) {
        int pos = start;
        while (pos + 8 <= end) {
            int type = u16(data, pos);
            int headerSize = u16(data, pos + 2);
            int size = u32(data, pos + 4);
            if (size < 8 || pos + size > end) return;
            if (type == 0x0002) collectOverlayables(data, pos + headerSize, pos + size, false, removals);
            else if (type == 0x0200) collectOverlayables(data, pos + headerSize, pos + size, true, removals);
            else if (inPackage && type == OVERLAYABLE && headerSize < COMPLETE_OVERLAY_HEADER) removals.add(new int[] {pos, size});
            pos += size;
        }
    }

    private static void shrinkPackages(byte[] data, int start, int end, List<int[]> removals) {
        int pos = start;
        while (pos + 8 <= end) {
            int type = u16(data, pos);
            int headerSize = u16(data, pos + 2);
            int size = u32(data, pos + 4);
            if (size < 8 || pos + size > end) return;
            if (type == 0x0200) {
                int lost = 0;
                for (int[] range : removals) {
                    if (range[0] >= pos && range[0] + range[1] <= pos + size) lost += range[1];
                }
                if (lost > 0) setU32(data, pos + 4, size - lost);
                shrinkPackages(data, pos + headerSize, pos + size, removals);
            } else if (type == 0x0002) {
                shrinkPackages(data, pos + headerSize, pos + size, removals);
            }
            pos += size;
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int u32(byte[] data, int offset) {
        return (data[offset] & 0xff)
            | ((data[offset + 1] & 0xff) << 8)
            | ((data[offset + 2] & 0xff) << 16)
            | ((data[offset + 3] & 0xff) << 24);
    }

    private static void setU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
