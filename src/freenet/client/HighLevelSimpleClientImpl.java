package freenet.client;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.HashMap;

import freenet.client.events.ClientEventListener;
import freenet.client.events.ClientEventProducer;
import freenet.client.events.EventLogger;
import freenet.client.events.SimpleEventProducer;
import freenet.crypt.RandomSource;
import freenet.keys.ClientCHK;
import freenet.keys.ClientKey;
import freenet.keys.FreenetURI;
import freenet.keys.InsertableClientSSK;
import freenet.node.RequestStarterClient;
import freenet.node.SimpleLowLevelClient;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;

public class HighLevelSimpleClientImpl implements HighLevelSimpleClient {

	private final SimpleLowLevelClient client;
	private final ArchiveManager archiveManager;
	private final BucketFactory bucketFactory;
	/** One CEP for all requests and inserts */
	private final ClientEventProducer globalEventProducer;
	private long curMaxLength;
	private long curMaxTempLength;
	private int curMaxMetadataLength;
	private final RandomSource random;
	private final RequestStarterClient requestStarter;
	private final RequestStarterClient insertStarter;
	/** See comments in Node */
	private final boolean cacheLocalRequests;
	static final int MAX_RECURSION = 10;
	static final int MAX_ARCHIVE_RESTARTS = 2;
	static final boolean DONT_ENTER_IMPLICIT_ARCHIVES = true;
	/** Number of threads used by a splitfile fetch */
	static final int SPLITFILE_THREADS = 20;
	/** Number of retries allowed per block in a splitfile. Must be at least 1 as 
	 * on the first try we just check the datastore.
	 */
	static final int SPLITFILE_BLOCK_RETRIES = 5;
	/** Number of retries allowed on non-splitfile fetches. Unlike above, we always
	 * go to network. */
	static final int NON_SPLITFILE_RETRIES = 2;
	/** Whether to fetch splitfiles. Don't turn this off! */
	static final boolean FETCH_SPLITFILES = true;
	/** Whether to follow redirects etc. If false, we only fetch a plain block of data. 
	 * Don't turn this off either! */
	static final boolean FOLLOW_REDIRECTS = true;
	/** If set, only check the local datastore, don't send an actual request out.
	 * Don't turn this off either. */
	static final boolean LOCAL_REQUESTS_ONLY = false;
	static final int SPLITFILE_INSERT_THREADS = 20;
	/** Number of retries on inserts */
	static final int INSERT_RETRIES = 10;
	/** Number of RNFs on insert that make a success, or -1 on large networks */
	static final int CONSECUTIVE_RNFS_ASSUME_SUCCESS = 2;
	// going by memory usage only; 4kB per stripe
	static final int MAX_SPLITFILE_BLOCKS_PER_SEGMENT = 1024;
	static final int MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 1536;
	static final int SPLITFILE_BLOCKS_PER_SEGMENT = 128;
	static final int SPLITFILE_CHECK_BLOCKS_PER_SEGMENT = 64;
	
	
	public HighLevelSimpleClientImpl(SimpleLowLevelClient client, ArchiveManager mgr, BucketFactory bf, RandomSource r, RequestStarterClient requestStarterClient, RequestStarterClient insertStarterClient, boolean cacheLocalRequests) {
		this.client = client;
		archiveManager = mgr;
		bucketFactory = bf;
		random = r;
		this.globalEventProducer = new SimpleEventProducer();
		globalEventProducer.addEventListener(new EventLogger(Logger.MINOR));
		curMaxLength = Long.MAX_VALUE;
		curMaxTempLength = Long.MAX_VALUE;
		curMaxMetadataLength = 1024 * 1024;
		this.requestStarter = requestStarterClient;
		this.insertStarter = insertStarterClient;
		this.cacheLocalRequests = cacheLocalRequests;
	}
	
	public void setMaxLength(long maxLength) {
		curMaxLength = maxLength;
	}

	public void setMaxIntermediateLength(long maxIntermediateLength) {
		curMaxTempLength = maxIntermediateLength;
	}

	/**
	 * Fetch a key. Either returns the data, or throws an exception.
	 */
	public FetchResult fetch(FreenetURI uri) throws FetchException {
		if(uri == null) throw new NullPointerException();
		FetcherContext context = new FetcherContext(client, curMaxLength, curMaxTempLength, curMaxMetadataLength, 
				MAX_RECURSION, MAX_ARCHIVE_RESTARTS, DONT_ENTER_IMPLICIT_ARCHIVES, 
				SPLITFILE_THREADS, SPLITFILE_BLOCK_RETRIES, NON_SPLITFILE_RETRIES,
				FETCH_SPLITFILES, FOLLOW_REDIRECTS, LOCAL_REQUESTS_ONLY,
				MAX_SPLITFILE_BLOCKS_PER_SEGMENT, MAX_SPLITFILE_CHECK_BLOCKS_PER_SEGMENT,
				random, archiveManager, bucketFactory, globalEventProducer, requestStarter, cacheLocalRequests);
		Fetcher f = new Fetcher(uri, context);
		return f.run();
	}

	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly) throws InserterException {
		InserterContext context = new InserterContext(client, bucketFactory, random, INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_INSERT_THREADS, SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, globalEventProducer, insertStarter, cacheLocalRequests);
		FileInserter i = new FileInserter(context);
		return i.run(insert, false, getCHKOnly, false, null);
	}

	public FreenetURI insert(InsertBlock insert, boolean getCHKOnly, boolean metadata) throws InserterException {
		InserterContext context = new InserterContext(client, bucketFactory, random, INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_INSERT_THREADS, SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, globalEventProducer, insertStarter, cacheLocalRequests);
		FileInserter i = new FileInserter(context);
		return i.run(insert, metadata, getCHKOnly, false, null);
	}
	
	public FreenetURI insertRedirect(FreenetURI insertURI, FreenetURI targetURI) throws InserterException {
		Metadata m = new Metadata(Metadata.SIMPLE_REDIRECT, targetURI, new ClientMetadata());
		Bucket b;
		try {
			b = BucketTools.makeImmutableBucket(bucketFactory, m.writeToByteArray());
		} catch (IOException e) {
			Logger.error(this, "Bucket error: "+e);
			throw new InserterException(InserterException.INTERNAL_ERROR, e, null);
		}
		ClientKey k;
		InsertBlock block = new InsertBlock(b, new ClientMetadata(), insertURI);
		InserterContext context = new InserterContext(client, bucketFactory, random, INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_INSERT_THREADS, SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, globalEventProducer, insertStarter, cacheLocalRequests);
		FileInserter i = new FileInserter(context);
		return i.run(block, true, false, false, null);
	}

	public FreenetURI insertManifest(FreenetURI insertURI, HashMap bucketsByName, String defaultName) throws InserterException {
		InserterContext context = new InserterContext(client, bucketFactory, random, INSERT_RETRIES, CONSECUTIVE_RNFS_ASSUME_SUCCESS,
				SPLITFILE_INSERT_THREADS, SPLITFILE_BLOCKS_PER_SEGMENT, SPLITFILE_CHECK_BLOCKS_PER_SEGMENT, globalEventProducer, insertStarter, cacheLocalRequests);
		MultiFileInserter mfi = new MultiFileInserter(insertURI, bucketsByName, context, defaultName);
		return mfi.run();
	}
	
	public void addGlobalHook(ClientEventListener listener) {
		globalEventProducer.addEventListener(listener);
	}
}
