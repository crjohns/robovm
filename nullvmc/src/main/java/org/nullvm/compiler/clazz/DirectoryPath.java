/*
 * Copyright (C) 2010 The NullVM Open Source Project
 *
 * TODO: Insert proper license header.
 */
package org.nullvm.compiler.clazz;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.io.FileUtils;

/**
 *
 * @version $Id$
 */
public class DirectoryPath implements Path {
    private final File dir;
    private final Clazzes clazzes;
    private List<Clazz> clazzList = null;
    
    DirectoryPath(File dir, Clazzes clazzes) {
        this.dir = dir;
        this.clazzes = clazzes;
    }
    
    public File getDir() {
        return dir;
    }
    
    private Clazz createClazz(final File f) {
        return new Clazz(f.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1), clazzes) {
            byte[] bytes = null;
            public byte[] getBytes() throws IOException {
                if (bytes == null) {
                    bytes = FileUtils.readFileToByteArray(f);
                }
                return bytes;
            }
        };
    }
    
    public List<Clazz> list() {
        if (clazzList == null) {
            clazzList = new LinkedList<Clazz>();
            for (File f : listClassFiles()) {
                clazzList.add(createClazz(f));
            }
        }
        return Collections.unmodifiableList(clazzList);
    }
    
    @SuppressWarnings("unchecked")
    private List<File> listClassFiles() {
        ArrayList<File> files = new ArrayList<File>((Collection<File>) FileUtils.listFiles(dir, new String[] {"class"}, true));
        Collections.sort(files);
        return files;
    }
}
