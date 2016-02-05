package com.dropbox.core.examples.longpoll;

import com.dropbox.core.DbxApiException;
import com.dropbox.core.DbxAuthInfo;
import com.dropbox.core.DbxException;
import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.DbxWebAuth;
import com.dropbox.core.json.JsonReader;
import com.dropbox.core.http.StandardHttpRequestor;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.DbxFiles;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An example command-line application that demonstrates how to use
 * longpolling to watch for file changes in a Dropbox account.
 */
public class Main {
    /**
     * Will perform longpoll request for changes in the user's Dropbox
     * account and display those changes. This is more efficient that
     * periodic polling the endpoint.
     */
    public static void longpoll(DbxAuthInfo auth, String path) throws IOException {
        long longpollTimeoutSecs = TimeUnit.MINUTES.toSeconds(2);

        // need 2 Dropbox clients for making calls:
        //
        // (1) One for longpoll requests, with its read timeout set longer than our polling timeout
        // (2) One for all other requests, with its read timeout set to the default, shorter timeout
        //
        StandardHttpRequestor.Config config = StandardHttpRequestor.Config.DEFAULT_INSTANCE;
        StandardHttpRequestor.Config longpollConfig = config.copy()
            .withReadTimeout(longpollTimeoutSecs + 1, TimeUnit.SECONDS)
            .build();

        DbxClientV2 dbxClient = createClient(auth, config);
        DbxClientV2 dbxLongpollClient = createClient(auth, longpollConfig);

        try {
            // We only care about file changes, not existing files, so grab latest cursor for this
            // path and then longpoll for changes.
            String cursor = getLatestCursor(dbxClient, path);

            System.out.println("Longpolling for changes... press CTRL-C to exit.");
            while (true) {
                // will block for longpollTimeoutSecs or until a change is made in the folder
                DbxFiles.ListFolderLongpollResult result = dbxLongpollClient.files.listFolderLongpoll(cursor, longpollTimeoutSecs);

                // we have changes, list them
                if (result.changes) {
                    cursor = printChanges(dbxClient, cursor);
                }

                // we were asked to back off from our polling, wait the requested amount of seconds
                // before issuing another longpoll request.
                if (result.backoff != null) {
                    System.out.printf("\n\nBacking off for %d seconds\n\n", result.backoff);
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(result.backoff));
                    } catch (InterruptedException ex) {
                        System.exit(0);
                    }
                }
            }
        } catch (DbxApiException ex) {
            // if a user message is available, try using that instead
            String message = ex.userMessage != null ? ex.userMessage.toString() : ex.getMessage();
            System.err.println("Error making API call: " + message);
            System.exit(1);
            return;
        } catch (DbxException ex) {
            System.err.println("Error making API call: " + ex.getMessage());
            System.exit(1);
            return;
        }
    }

    /**
     * Create a new Dropbox client using the given authentication
     * information and HTTP client config.
     *
     * @param auth Authentication information
     * @param config HTTP request configuration
     *
     * @return new Dropbox V2 client
     */
    private static DbxClientV2 createClient(DbxAuthInfo auth, StandardHttpRequestor.Config config) {
        String clientUserAgentId = "examples-longpoll";
        String userLocale = Locale.getDefault().toString();
        StandardHttpRequestor requestor = new StandardHttpRequestor(config);
        DbxRequestConfig requestConfig = new DbxRequestConfig(clientUserAgentId, userLocale, requestor);

        return new DbxClientV2(requestConfig, auth.accessToken, auth.host);
    }

    /**
     * Returns latest cursor for listing changes to a directory in
     * Dropbox with the given path.
     *
     * @param dbxClient Dropbox client to use for fetching the latest cursor
     * @param path path to directory in Dropbox
     *
     * @return cursor for listing changes to the given Dropbox directory
     */
    private static String getLatestCursor(DbxClientV2 dbxClient, String path)
        throws DbxApiException, DbxException {
        DbxFiles.ListFolderGetLatestCursorResult result = dbxClient.files.listFolderGetLatestCursorBuilder(path)
            .includeDeleted(true)
            .includeMediaInfo(false)
            .recursive(true)
            .start();
        return result.cursor;
    }

    /**
     * Prints changes made to a folder in Dropbox since the given
     * cursor was retrieved.
     *
     * @param dbxClient Dropbox client to use for fetching folder changes
     * @param cursor lastest cursor received since last set of changes
     *
     * @return latest cursor after changes
     */
    private static String printChanges(DbxClientV2 client, String cursor)
        throws DbxApiException, DbxException {

        while (true) {
            DbxFiles.ListFolderResult result = client.files.listFolderContinue(cursor);
            for (DbxFiles.Metadata metadata : result.entries) {
                String type;
                String details;
                if (metadata instanceof DbxFiles.FileMetadata) {
                    DbxFiles.FileMetadata fileMetadata = (DbxFiles.FileMetadata) metadata;
                    type = "file";
                    details = "(rev=" + fileMetadata.rev + ")";
                } else if (metadata instanceof DbxFiles.FolderMetadata) {
                    DbxFiles.FolderMetadata folderMetadata = (DbxFiles.FolderMetadata) metadata;
                    type = "folder";
                    details = folderMetadata.sharingInfo != null ? "(shared)" : "";
                } else if (metadata instanceof DbxFiles.DeletedMetadata) {
                    type = "deleted";
                    details = "";
                } else {
                    throw new IllegalStateException("Unrecognized metadata type: " + metadata.getClass());
                }

                System.out.printf("\t%10s %24s \"%s\"\n", type, details, metadata.pathLower);
            }
            // update cursor to fetch remaining results
            cursor = result.cursor;

            if (!result.hasMore) {
                break;
            }
        }

        return cursor;
    }

    public static void main(String[] args) throws IOException {
        // Only display important log messages.
        Logger.getLogger("").setLevel(Level.WARNING);

        if (args.length == 0) {
            System.out.println("");
            System.out.println("Usage: COMMAND <auth-file> <dropbox-path>");
            System.out.println("");
            System.out.println(" <auth-file>: An \"auth file\" that contains the information necessary to make");
            System.out.println("    an authorized Dropbox API request.  Generate this file using the \"authorize\"");
            System.out.println("    example program.");
            System.out.println("");
            System.out.println(" <dropbox-path>: The path on Dropbox to watch for changes.");
            System.out.println("");
            return;
        }

        if (args.length != 2) {
            System.err.println("Expecting exactly 2 arguments, got " + args.length + ".");
            System.err.println("Run with no arguments for help.");
            System.exit(1); return;
        }

        String authFile = args[0];
        String path = args[1];

        // Read auth info file.
        DbxAuthInfo auth;
        try {
            auth = DbxAuthInfo.Reader.readFromFile(authFile);
        }
        catch (JsonReader.FileLoadException ex) {
            System.err.println("Error loading <auth-file>: " + ex.getMessage());
            System.exit(1); return;
        }

        longpoll(auth, path);
    }
}