package org.jruby.util;

import jnr.posix.FileStat;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.jar.JarEntry;

abstract class JarResource extends AbstractFileResource {

    private static final JarCache jarCache = new JarCache();

    public static JarResource create(String pathname) {
        int bang = pathname.indexOf('!');
        if (bang == -1) return null;  // no ! no jar!

        if (pathname.startsWith("jar:")) {
            if (pathname.startsWith("file:", 4)) {
                pathname = pathname.substring(9); bang -= 9; // 4 + 5
            }
            else {
                pathname = pathname.substring(4); bang -= 4;
            }
        }
        else if (pathname.startsWith("file:")) {
            pathname = pathname.substring(5); bang -= 5;
        }

        String jarPath = pathname.substring(0, bang);
        String entryPath = pathname.substring(bang + 1);
        // normalize path -- issue #2017
        if (entryPath.startsWith("//")) entryPath = entryPath.substring(1);

        // TODO: Do we really need to support both test.jar!foo/bar.rb and test.jar!/foo/bar.rb cases?
        JarResource resource = createJarResource(jarPath, entryPath, false);

        if (resource == null && entryPath.startsWith("/")) {
            resource = createJarResource(jarPath, entryPath.substring(1), true);
        }

        return resource;
    }

    private static JarResource createJarResource(String jarPath, String entryPath, boolean rootSlashPrefix) {
        JarCache.JarIndex index = jarCache.getIndex(jarPath);

        if (index == null) { // Jar doesn't exist
            try {
                jarPath = URLDecoder.decode(jarPath, "UTF-8");
                entryPath = URLDecoder.decode(entryPath, "UTF-8");
            }
            catch (IllegalArgumentException e) {
                // something in the path did not decode, so it's probably not a URI
                // See jruby/jruby#2264.
                return null;
            }
            catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
            index = jarCache.getIndex(jarPath);

            if (index == null) return null; // Jar doesn't exist
        }

        // Try it as directory first, because jars tend to have foo/ entries
        // and it's not really possible disambiguate between files and directories.
        String[] entries = index.getDirEntries(entryPath);
        if (entries != null) {
            return new JarDirectoryResource(jarPath, rootSlashPrefix, entryPath, entries);
        }
        if (entryPath.length() > 1 && entryPath.endsWith("/")) {  // in case 'foo/' passed
            entries = index.getDirEntries(entryPath.substring(0, entryPath.length() - 1));

            if (entries != null) {
                return new JarDirectoryResource(jarPath, rootSlashPrefix, entryPath, entries);
            }
        }

        JarEntry jarEntry = index.getJarEntry(entryPath);
        if (jarEntry != null) {
            return new JarFileResource(jarPath, rootSlashPrefix, index, jarEntry);
        }

        return null;
    }

    public static boolean removeJarResource(String jarPath){
        return jarCache.remove(jarPath);
    }

    private final String jarPrefix;
    private final JarFileStat fileStat;

    protected JarResource(String jarPath, boolean rootSlashPrefix) {
        this.jarPrefix = rootSlashPrefix ? jarPath + "!/" : jarPath + "!";
        this.fileStat = new JarFileStat(this);
    }

    @Override
    public String absolutePath() {
        return jarPrefix + entryName();
    }

    @Override
    public String canonicalPath() {
        return absolutePath();
    }

    @Override
    public boolean exists() {
        // If a jar resource got created, then it always corresponds to some kind of resource
        return true;
    }

    @Override
    public boolean canRead() {
        // Can always read from a jar
        return true;
    }

    @Override
    public boolean canWrite() {
        return false;
    }

    @Override
    public boolean isSymLink() {
        // Jar archives shouldn't contain symbolic links, or it would break portability. `jar`
        // command behavior seems to comform to that (it unwraps syumbolic links when creating a jar
        // and replaces symbolic links with regular file when extracting from a zip that contains
        // symbolic links). Also see:
        // http://www.linuxquestions.org/questions/linux-general-1/how-to-create-jar-files-with-symbolic-links-639381/
        return false;
    }

    @Override
    public FileStat stat() {
        return fileStat;
    }

    @Override
    public FileStat lstat() {
      return stat(); // jars don't have symbolic links, so lstat == stat
    }

    @Override
    public JRubyFile hackyGetJRubyFile() {
      return JRubyFile.DUMMY;
    }

    abstract protected String entryName();
}
