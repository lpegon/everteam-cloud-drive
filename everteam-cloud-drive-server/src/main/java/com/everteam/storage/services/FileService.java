package com.everteam.storage.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.everteam.storage.common.model.ESFile;
import com.everteam.storage.common.model.ESFileList;
import com.everteam.storage.common.model.ESPermission;
import com.everteam.storage.drive.IDrive;
import com.everteam.storage.drive.OAuth2DriveImpl;
import com.everteam.storage.jackson.ESFileSerializer;
import com.everteam.storage.utils.ESFileId;
import com.everteam.storage.utils.FileInfo;
import com.everteam.storage.utils.Messages;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class FileService {

    private final static Logger LOG = LoggerFactory.getLogger(FileService.class);

    @Value("${storage.watcherDirectory:#{'/home/local/temp'}}")
    private String watcherDirectory;

    @Autowired
    RepositoryService driveManager;
    
    @Autowired
    private ObjectMapper jacksonObjectMapper;
    
    @Autowired
    private Messages messages;
    
    

    public ESFile getFile(ESFileId fileId, boolean addPermissions, Boolean addChecksum) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);
        return drive.getFile(fileId.getPath(), addPermissions, addChecksum)
                .repositoryId(fileId.getRepositoryId());
    }
    

    public ESFileId copy(ESFileId sourceId, ESFileId targetId) throws IOException, GeneralSecurityException {
        if (isRoot(sourceId)) {
            throw new IOException(messages.get("error.cannotcopyroot"));
        }
        
        IDrive sourcedrive = getDrive(sourceId);

        IDrive targetDrive = getDrive(targetId);
        // TODO : Have to handle if we copy file or directory and its content, for now just handling file copy
        ByteArrayOutputStream baOS = new ByteArrayOutputStream();
        sourcedrive.downloadTo(sourceId.getPath(), baOS);
        ESFile sourceFile = sourcedrive.getFile(sourceId.getPath(), false, false);
        FileInfo info = new FileInfo(sourceFile.getName(), sourceFile.getDescription(),
                sourceFile.getMimeType(), sourceFile.getFileSize(), new ByteArrayInputStream(baOS.toByteArray()));
        String newFileId = targetDrive.insertFile(targetId.getPath(), info);
        return new ESFileId(targetId.getRepositoryId(), newFileId);
        
    }

    


    public ESFileList getChildren(ESFileId fileId, boolean addPermissions, Boolean addChecksum, int maxSize) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);
        ESFileList files = drive.children(fileId.getPath(), addPermissions, addChecksum, maxSize);
        for (ESFile file : files.getItems()) {
            file.setRepositoryId(fileId.getRepositoryId());
        }
        return files;
        
        
    }

    public void delete(ESFileId fileId) throws IOException, GeneralSecurityException {
        if (isRoot(fileId)) {
            throw new IOException(messages.get("error.cannotdeleteroot"));
        }
        IDrive drive = getDrive(fileId);
        drive.delete(fileId.getPath());
    }

    public List<ESPermission> getPermissions(ESFileId fileId) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);

        return drive.getPermissions(fileId.getPath());
    }

    public void downloadTo(ESFileId fileId, OutputStream outputStream) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);
        if (drive.isFolder(fileId.getPath())) {
            throw new IOException(messages.get("error.cannotdownloadfolder"));
        }

        drive.downloadTo(fileId.getPath(), outputStream);

    }

    public byte[] getFileContent(ESFileId fileId) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);

        if (drive.isFolder(fileId.getPath())) {
            throw new IOException(messages.get("error.cannotdownloadfolder"));
        }
        
        ByteArrayOutputStream baOS = new ByteArrayOutputStream();
        drive.downloadTo(fileId.getPath(), baOS);
        return baOS.toByteArray();
    }

    public void update(ESFileId fileId, FileInfo info) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);
        if (drive.isFolder(fileId.getPath())) {
            throw new IOException(messages.get("error.cannotuploadfolder"));
        }
        drive.update(fileId.getPath(), info);
    }
    
    public ESFileId createFolder(ESFileId parentId, String name, String description) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(parentId);
        String newFileId = null;
        if (!drive.isFolder(parentId.getPath())) {
            throw new IOException(messages.get("error.cannotcreateiteminfile"));
        }
        newFileId = drive.insertFolder(parentId.getPath(), name, description);
        return new ESFileId(parentId.getRepositoryId(), newFileId);
    }

    public ESFileId createFile(ESFileId parentId, FileInfo info)
            throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(parentId);
        String newFileId = null;
        if (!drive.isFolder(parentId.getPath())) {
            throw new IOException(messages.get("error.cannotcreateiteminfile"));
        }
        newFileId = drive.insertFile(parentId.getPath(), info);
        return new ESFileId(parentId.getRepositoryId(), newFileId);
    }

    public void checkUpdates(ESFileId fileId, OffsetDateTime fromDate) throws IOException, GeneralSecurityException {
        IDrive drive = getDrive(fileId);
        CheckUpdate cu = new CheckUpdate(this, drive, fileId.getPath(), fromDate);
        Thread thread = new Thread(cu);
        thread.start();
    }

    
    protected IDrive getDrive(ESFileId fileId) throws GeneralSecurityException, IOException {
        IDrive drive = driveManager.getDrive(fileId.getRepositoryId());
        if (drive instanceof OAuth2DriveImpl) {
            ((OAuth2DriveImpl) drive).authorize();
        }
        return drive;
    }
    
    protected boolean isRoot(ESFileId fileId) {
        return fileId.getPath() == null || fileId.getPath().isEmpty();
    }
    
    protected void exportWatcherFile(ESFile file) {
        try {
            Path targetDir = Paths.get(watcherDirectory)
            .resolve(file.getRepositoryId())
            .resolve(String.valueOf(file.getLastModifiedTime().getYear()))
            .resolve(String.valueOf(file.getLastModifiedTime().getMonth()))
            .resolve(String.valueOf(file.getLastModifiedTime().getDayOfMonth()));
            
            targetDir.toFile().mkdirs();
            
            Path target = targetDir.resolve(ESFileSerializer.serialize(file.getRepositoryId(), file.getId()));            
            
            jacksonObjectMapper.writeValue(target.toFile(), file);
        } catch (IOException | URISyntaxException e) {
            LOG.error(e.getMessage(), e);
        }
    }
    
    

    private static class CheckUpdate implements Runnable {
        private final static Logger LOG = LoggerFactory.getLogger(CheckUpdate.class);

        FileService service;
        IDrive drive;
        String fileId;
        OffsetDateTime fromDate;

        public CheckUpdate(FileService service, IDrive drive, String fileId, OffsetDateTime fromDate) {
            this.service= service;
            this.drive = drive;
            this.fileId = fileId;
            this.fromDate = fromDate;
        }

        @Override
        public void run() {
            try {
                drive.checkUpdates(fileId, fromDate, new FileConsumer( service, drive.getRepository().getId()));
            } catch (IOException e) {
                LOG.error(e.getMessage(), e);
            }
        }
    }

    private static class FileConsumer implements Consumer<ESFile> {
        FileService fileService;
        String repositoryId;
        
        public FileConsumer(FileService fileService, String repositoryId) {
            this.fileService = fileService;
            this.repositoryId = repositoryId;
        }
        
        @Override
        public void accept(ESFile file) {
            fileService.exportWatcherFile(file.repositoryId(repositoryId));
            
        }

      
        
        
    }

}
