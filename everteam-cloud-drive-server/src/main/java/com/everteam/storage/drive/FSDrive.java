package com.everteam.storage.drive;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.FileNameMap;
import java.net.URLConnection;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipal;
import java.security.GeneralSecurityException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import com.everteam.storage.common.model.ESFile;
import com.everteam.storage.common.model.ESFileList;
import com.everteam.storage.common.model.ESParent;
import com.everteam.storage.common.model.ESPermission;
import com.everteam.storage.common.model.ESPermission.AccountTypeEnum;
import com.everteam.storage.common.model.ESPermission.RolesEnum;
import com.everteam.storage.common.model.ESPermission.TypeEnum;
import com.everteam.storage.common.model.ESRepository;
import com.everteam.storage.common.model.ESUser;
import com.everteam.storage.utils.FileInfo;

@Component(value="fs")
@Scope("prototype")
public class FSDrive extends DriveImpl {

    private final static Logger LOG = LoggerFactory.getLogger(FSDrive.class);

    @Value("${storage.fs.maxFilesInDirectory:#{1000}}")
    private int maxFilesInDirectory;

    @Value("${storage.fs.keepEmptyDirectory:#{false}}")
    private boolean keepEmptyDirectory;

    @Value("${storage.fs.uri.depth:#{4}}")
    private int fileDepth;

    @Override
    public void init(ESRepository repository) throws IOException, GeneralSecurityException {
        super.init(repository);
        Path rootDirectory = Paths.get(getRepository().getRootDirectory());
        Files.createDirectories(rootDirectory);
    }

    @Override
    public ESFileList children(String parentId, boolean addPermissions, boolean addChecksum, int maxSize) throws IOException {
        Path p = buildPath(parentId);

        List<ESFile> items = new ArrayList<>(100);

        try (Stream<Path> paths = Files.list(p)) {
            paths.limit(maxSize).forEach(new Consumer<Path>() {
                @Override
                public void accept(Path t) {
                    try {
                        items.add(getFile(t, addPermissions, addChecksum));
                    } catch (IOException e) {
                        LOG.error(e.getMessage(), e);
                    }
                }
            });
        }

        return new ESFileList().items(items);
    }

    @Override
    public void downloadTo(String fileId, OutputStream outputstream) throws IOException {
        Path p = buildPath(fileId);
        Files.copy(p, outputstream);
    }

    @Override
    public String insertFile(String fileId, FileInfo info) throws IOException {
        String newId = null;
        Path path = buildPath(fileId);
        Path newFilePath = path.resolve(info.getName());
        Files.copy(info.getInputStream(), newFilePath);
        newId = buildFileId(newFilePath);
        return newId;
    }
    
    @Override
    public String insertFolder(String fileId, String name, String description) throws IOException {
        String newId = null;
        Path path = buildPath(fileId);
        Path newFilePath = path.resolve(name);
        Files.createDirectory(newFilePath);
        newId = buildFileId(newFilePath);
        return newId;
    }
    
    @Override
    public boolean isFolder(String fileId) {
        Path path = buildPath(fileId);
        return path.toFile().isDirectory();
    }

    @Override
    public List<ESPermission> getPermissions(String fileId) throws IOException {
        return getPermissions(buildPath(fileId));
    }

    @Override
    public void delete(String repositoryFileId) throws IOException {
        Path path = buildPath(repositoryFileId);
        deleteAllInPath(path);
    }

    @Override
    public ESFile getFile(String fileId, boolean addPermissions,  boolean addChecksum) throws IOException {
        Path path = buildPath(fileId);
        return getFile(path, addPermissions, addChecksum);
    }

    @Override
    public void update(String fileId, FileInfo info) throws IOException {
        Path path = buildPath(fileId);
        Files.copy(info.getInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
        //description can't be manage is this drive 
    }

    @Override
    public void checkUpdates(String fileId, OffsetDateTime fromDate, Consumer<ESFile> consumer) throws IOException {
        Path start = buildPath(fileId);
        
        try (Stream<Path> paths = Files
                .find(start, 20, (path, attr) -> !attr.isDirectory() && 
        OffsetDateTime.ofInstant(attr.lastModifiedTime().toInstant(), ZoneOffset.UTC).isAfter(fromDate))) {
            paths.forEach(new Consumer<Path>() {
                @Override
                public void accept(Path t) {
                    try {
                        
                        consumer.accept(getFile(buildFileId(t), false, false));
                        
                    } catch (IOException e) {
                        LOG.error(e.getMessage(), e);
                    }
                }
            });
        }
    }
    
    @Override
    public boolean exists(String fileId) {
        return Files.exists(buildPath(fileId));
    }
    
    
    
    
    
    private ESFile getFile(Path path, boolean addPermissions, boolean addChecksum) throws IOException {
        // get mime_type
        File file = path.toFile();
        
        
        
        if (!file.exists()) {
            throw new FileNotFoundException();
        }
        FileNameMap fileNameMap = URLConnection.getFileNameMap();
        String type = fileNameMap.getContentTypeFor(file.getName());

        // build a ESFile from file object
        ESFile esfile = new ESFile()
                .id(buildRelativePath(path).toString())
                .name(file.getName()).mimeType(type).directory(file.isDirectory());

        if (!file.isDirectory()) {
            esfile.fileSize(file.length());
            if (addChecksum) {
                String md5 = DigestUtils.md5DigestAsHex(getFileContent(path));
                esfile.setChecksum(md5);
            }
        }

        // fill date-time properties from attributes.
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            FileTime lastAccessTime = attrs.lastAccessTime();
            esfile.lastAccessTime(OffsetDateTime.ofInstant(lastAccessTime.toInstant(), ZoneOffset.UTC));

            FileTime lastModifiedTime = attrs.lastModifiedTime();
            esfile.lastModifiedTime(OffsetDateTime.ofInstant(lastModifiedTime.toInstant(), ZoneOffset.UTC));

            FileTime creationTime = attrs.creationTime();
            esfile.creationTime(OffsetDateTime.ofInstant(creationTime.toInstant(), ZoneOffset.UTC));
        } catch (IOException e) {
            LOG.error(e.getMessage(), e);
        }

        FileOwnerAttributeView ownerAttributeView = Files.getFileAttributeView(path, FileOwnerAttributeView.class);
        UserPrincipal owner = ownerAttributeView.getOwner();
        esfile.addOwnersItem(new ESUser().id(owner.getName()).displayName(owner.toString()));

        if (addPermissions) {
            esfile.setPermissions(getPermissions(path));
        } else {
            esfile.setPermissions(null);
        }

        Path repositoryPath = Paths.get(repository.getRootDirectory());
        Path parentPath = path.getParent();
        Path relativePath = buildRelativePath(parentPath);

        List<String> paths = new ArrayList<>();
        if (relativePath.toString().length() > 0) {
            relativePath.forEach(new Consumer<Path>() {
                @Override
                public void accept(Path t) {
                    paths.add(t.toString());
                }
            });
        }

        if (parentPath.startsWith(repositoryPath)) {
            esfile.addParentsItem(new ESParent()
                    .id(relativePath.toString())
                    .paths(paths));
        }

        return esfile;
    }

    private Path buildPath(String fileId) {
        Path p;
        ESRepository repository = getRepository();
        p = Paths.get(repository.getRootDirectory());
        if (fileId != null) {
            p = p.resolve(fileId);
        }
        return p;
    }

    private String buildFileId(Path filePath) throws IOException {
        return buildRelativePath(filePath).toString();
    }

    private Path buildRelativePath(Path filePath) throws IOException {
        return Paths.get(this.getRepository().getRootDirectory()).relativize(filePath);
    }

    private void deleteAllInPath(Path directory) throws IOException {
        if (directory != null) {
            Files.walk(directory, FileVisitOption.FOLLOW_LINKS).sorted(Comparator.reverseOrder()).map(Path::toFile)
                    .forEach(File::delete);
            Files.deleteIfExists(directory);
        }
    }

    private List<ESPermission> getPermissions(Path p) throws IOException {
        List<ESPermission> permissions = new ArrayList<>();
        
        if (Files.getFileStore(p).supportsFileAttributeView(AclFileAttributeView.class)) {
        
            AclFileAttributeView aclView = Files.getFileAttributeView(p, AclFileAttributeView.class);
            for (AclEntry entry : aclView.getAcl()) {
    
                TypeEnum type = null;
                switch (entry.type()) {
                case ALLOW:
                    type = TypeEnum.ALLOW;
                    break;
                case DENY:
                    type = TypeEnum.DENY;
                    break;
                default:
                    break;
                }
                if (type != null) {
                    TypeEnum f_type = type;
                    List<RolesEnum> roles = new ArrayList<>();
                    if (entry.permissions().contains(AclEntryPermission.READ_DATA)) {
                        roles.add(RolesEnum.READER);
                    }
                    if (entry.permissions().contains(AclEntryPermission.WRITE_DATA)) {
                        roles.add(RolesEnum.WRITER);
                    }
                    if (entry.permissions().contains(AclEntryPermission.WRITE_OWNER)) {
                        roles.add(RolesEnum.OWNER);
                    }
    
                    if (roles.size() > 0) {
                        ESPermission permission = new ESPermission().type(f_type).userId(entry.principal().getName())
                                .accountType(AccountTypeEnum.USER).roles(roles);
                        permissions.add(permission);
                    }
    
                }
            }
        }
        return permissions;
    }

    private byte[] getFileContent(Path path) throws IOException {
        ByteArrayOutputStream outputstream = new ByteArrayOutputStream();
        Files.copy(path, outputstream);
        return outputstream.toByteArray();
    }

   


}
