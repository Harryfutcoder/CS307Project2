package edu.sustech.cs307.system;

import edu.sustech.cs307.exception.DBException;
import edu.sustech.cs307.exception.ExceptionTypes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.pmw.tinylog.Logger;


public class TransactionManager {

    private final DBManager dbManager;
    private Snapshot transactionSnapshot;
    private boolean inTransaction;
    private final java.util.List<NamedSnapshot> savepoints = new java.util.ArrayList<>();

    private static class Snapshot {
        private final Path snapshotDir;
        private final java.util.Map<String, Integer> filePages;

        private Snapshot(Path snapshotDir, java.util.Map<String, Integer> filePages) {
            this.snapshotDir = snapshotDir;
            this.filePages = filePages;
        }
    }

    private static class NamedSnapshot {
        private final String name;
        private final Snapshot snapshot;

        private NamedSnapshot(String name, Snapshot snapshot) {
            this.name = name;
            this.snapshot = snapshot;
        }
    }


    public TransactionManager(DBManager dbManager) {
        this.dbManager = dbManager;
    }


    public void begin() throws DBException {
        if (inTransaction) {
            throw new DBException(ExceptionTypes.TransactionAlreadyActive());
        }
        transactionSnapshot = createSnapshotState();
        savepoints.clear();
        inTransaction = true;
    }


    public void commit() throws DBException {
        if (!inTransaction) {
            return;
        }
        dbManager.persistRuntimeState();
        clearSnapshots();
        inTransaction = false;
    }


    public void rollback() throws DBException {
        if (!inTransaction) {
            return;
        }
        restoreSnapshot(transactionSnapshot);
        clearSnapshots();
        inTransaction = false;
    }


    public void savepoint(String savepointName) throws DBException {
        requireTransaction();
        savepoints.add(new NamedSnapshot(savepointName, createSnapshotState()));
    }


    public void rollbackToSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepointIndex(savepointName);
        if (index == -1) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        NamedSnapshot target = savepoints.get(index);
        restoreSnapshot(target.snapshot);
        if (index + 1 < savepoints.size()) {
            savepoints.subList(index + 1, savepoints.size()).clear();
        }
    }


    public void releaseSavepoint(String savepointName) throws DBException {
        requireTransaction();
        int index = findLatestSavepointIndex(savepointName);
        if (index == -1) {
            throw new DBException(ExceptionTypes.SavepointDoesNotExist(savepointName));
        }
        savepoints.remove(index);
    }

    private Path createSnapshot() throws DBException {
        dbManager.persistRuntimeState();
        Path snapshotDir;
        try {
            snapshotDir = Files.createTempDirectory("cs307-txn-");
            copyDirectoryContents(getDbRoot(), snapshotDir);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        return snapshotDir;
    }

    private Path getDbRoot() {
        return Path.of(dbManager.getDiskManager().getCurrentDir());
    }

    private void copyDirectoryContents(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot)) {
            Files.createDirectories(targetRoot);
            return;
        }
        Files.createDirectories(targetRoot);
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private Snapshot createSnapshotState() throws DBException {
        Path snapshotDir = createSnapshot();
        return new Snapshot(snapshotDir, new java.util.HashMap<>(dbManager.getDiskManager().filePages));
    }

    private void restoreSnapshot(Snapshot snapshot) throws DBException {
        if (snapshot == null) {
            return;
        }
        try {
            Path dbRoot = getDbRoot();
            deleteDirectoryContents(dbRoot);
            copyDirectoryContents(snapshot.snapshotDir, dbRoot);
        } catch (IOException e) {
            throw new DBException(ExceptionTypes.BadIOError(e.getMessage()));
        }
        dbManager.getDiskManager().filePages = new java.util.HashMap<>(snapshot.filePages);
        dbManager.getBufferPool().reset();
        dbManager.getMetaManager().reloadFromDisk();
    }

    private void deleteDirectoryContents(Path root) throws IOException {
        if (!Files.exists(root)) {
            Files.createDirectories(root);
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (path.equals(root)) {
                    continue;
                }
                Files.deleteIfExists(path);
            }
        }
    }

    private void clearSnapshots() {
        if (transactionSnapshot != null) {
            try {
                deleteDirectoryContents(transactionSnapshot.snapshotDir);
                Files.deleteIfExists(transactionSnapshot.snapshotDir);
            } catch (IOException e) {
                Logger.warn("Failed to clean transaction snapshot {}", e.getMessage());
            }
        }
        for (NamedSnapshot savepoint : savepoints) {
            try {
                deleteDirectoryContents(savepoint.snapshot.snapshotDir);
                Files.deleteIfExists(savepoint.snapshot.snapshotDir);
            } catch (IOException e) {
                Logger.warn("Failed to clean savepoint snapshot {}", e.getMessage());
            }
        }
        transactionSnapshot = null;
        savepoints.clear();
    }

    private void requireTransaction() throws DBException {
        if (!inTransaction) {
            throw new DBException(ExceptionTypes.TransactionRequired());
        }
    }

    private int findLatestSavepointIndex(String savepointName) {
        for (int i = savepoints.size() - 1; i >= 0; i--) {
            if (savepoints.get(i).name.equals(savepointName)) {
                return i;
            }
        }
        return -1;
    }
}
