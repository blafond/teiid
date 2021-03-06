/*
 * Copyright Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags and
 * the COPYRIGHT.txt file distributed with this work.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.teiid.resource.adapter.ftp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;

import javax.resource.ResourceException;
import javax.resource.spi.InvalidPropertyException;

import org.apache.commons.net.ftp.FTPClient;
import org.jboss.vfs.VFS;
import org.jboss.vfs.VirtualFile;
import org.teiid.core.BundleUtil;
import org.teiid.core.types.InputStreamFactory;
import org.teiid.file.VirtualFileConnection;
import org.teiid.resource.spi.BasicConnection;
import org.teiid.translator.TranslatorException;

public class FtpFileConnectionImpl extends BasicConnection implements VirtualFileConnection {

    static class JBossVirtualFile implements org.teiid.file.VirtualFile {

        private VirtualFile file;

        public JBossVirtualFile(VirtualFile file) {
            this.file = file;
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public InputStreamFactory createInputStreamFactory() {
            return new InputStreamFactory() {

                @Override
                public InputStream getInputStream() throws IOException {
                    return file.openStream();
                }
            };
        }

        @Override
        public InputStream openInputStream(boolean lock) throws IOException {
            //locking not supported
            return file.openStream();
        }

        @Override
        public OutputStream openOutputStream(boolean lock) throws IOException {
            throw new IOException("not supported"); //$NON-NLS-1$
        }

        @Override
        public long getLastModified() {
            return file.getLastModified();
        }

        @Override
        public long getCreationTime() {
            //not supported through vfs
            return file.getLastModified();
        }

        @Override
        public long getSize() {
            return file.getSize();
        }

    }

    public static final BundleUtil UTIL = BundleUtil.getBundleUtil(FtpFileConnectionImpl.class);

    private VirtualFile mountPoint;
    private Map<String, String> fileMapping;
    private Closeable closeable;
    private final FTPClient client;

    public FtpFileConnectionImpl(FTPClient client, String pathname, Map<String, String> fileMapping) throws ResourceException {
        this.client = client;
        if(fileMapping == null) {
            this.fileMapping = Collections.emptyMap();
        } else {
            this.fileMapping = fileMapping;
        }

        try {
            if(this.client.cwd(pathname) != 250) {
                throw new InvalidPropertyException(UTIL.getString("parentdirectory_not_set")); //$NON-NLS-1$
            }
            this.client.changeWorkingDirectory(pathname);
            this.mountPoint = VFS.getChild(pathname);
            this.closeable = VFS.mount(mountPoint, new FtpFileSystem(this.client));
        } catch (IOException e) {
            throw new ResourceException(UTIL.getString("vfs_mount_error", pathname), e); //$NON-NLS-1$
        }

    }

    @Override
    public void close() throws ResourceException {
        try {
            this.closeable.close();
        } catch (IOException e) {
            throw new ResourceException(e);
        }
    }

    @Override
    public org.teiid.file.VirtualFile[] getFiles(String pattern) {
        VirtualFile file = this.getFile(pattern);
        if(file.exists()) {
            return new org.teiid.file.VirtualFile[]{new JBossVirtualFile(file)};
        }

        return null;
    }

    public VirtualFile getFile(String path) {
        if(path == null) {
            return this.mountPoint;
        }
        String altPath = fileMapping.get(path);
        if (altPath != null) {
            path = altPath;
        }
        return this.mountPoint.getChild(path);
    }

    @Override
    public void add(InputStream in, String path) throws TranslatorException {
        try {
            this.client.storeFile(path, in);
        } catch (IOException e) {
            throw new TranslatorException(e, UTIL.getString("ftp_failed_write", path, this.client.getReplyString())); //$NON-NLS-1$
        }
    }

    @Override
    public boolean remove(String path) {
        return this.mountPoint.getChild(path).delete();
    }

}
