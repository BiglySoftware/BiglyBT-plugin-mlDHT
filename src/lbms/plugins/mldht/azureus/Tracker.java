/*
 *    This file is part of mlDHT.
 * 
 *    mlDHT is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 2 of the License, or
 *    (at your option) any later version.
 * 
 *    mlDHT is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 * 
 *    You should have received a copy of the GNU General Public License
 *    along with mlDHT.  If not, see <http://www.gnu.org/licenses/>.
 */
package lbms.plugins.mldht.azureus;

import java.util.*;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import lbms.plugins.mldht.kad.*;
import lbms.plugins.mldht.kad.DHT.DHTtype;
import lbms.plugins.mldht.kad.tasks.PeerLookupTask;
import lbms.plugins.mldht.kad.tasks.Task;
import lbms.plugins.mldht.kad.tasks.TaskListener;

import com.biglybt.core.download.DownloadManager;
import com.biglybt.core.download.DownloadManagerState;
import com.biglybt.core.download.DownloadManagerStateAttributeListener;
import com.biglybt.core.util.AERunnable;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AsyncDispatcher;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadAnnounceResult;
import com.biglybt.pif.download.DownloadAttributeListener;
import com.biglybt.pif.download.DownloadListener;
import com.biglybt.pif.download.DownloadManagerListener;
import com.biglybt.pif.download.DownloadScrapeResult;
import com.biglybt.pif.download.DownloadTrackerListener;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.biglybt.pifimpl.local.PluginCoreUtils;

/**
 * @author Damokles
 *
 */
public class Tracker {

	public static final int					MAX_CONCURRENT_ANNOUNCES	= 8;
	public static final int					MAX_CONCURRENT_SCRAPES		= 1;

	public static final int					TRACKER_UPDATE_INTERVAL		= 10 * 1000;

	public static final int					SHORT_DELAY					= 60 * 1000;
	public static final int					VERY_SHORT_DELAY			= 5 * 1000;
	public static final int					METADATA_ANNOUNCE_DELAY		= 30 * 1000;

	public static final int					MIN_ANNOUNCE_INTERVAL		= 5 * 60 * 1000;
	//actually MIN is added to this
	public static final int					MAX_ANNOUNCE_INTERVAL		= 20 * 60 * 1000;
	
	public static final int					MIN_SCRAPE_INTERVAL		= 20 * 60 * 1000;
	//actually MIN is added to this
	public static final int					MAX_SCRAPE_INTERVAL		= 10 * 60 * 1000;

	public static final String				PEER_SOURCE_NAME			= "DHT"; // DownloadAnnounceResultPeer.PEERSOURCE_DHT;

	private List<Download>					currentAnnounces			= new LinkedList<>();
	private List<Download>					currentScrapes				= new LinkedList<>();
	private MlDHTPlugin						plugin;
	private boolean							running;
	private Random							random						= new Random();
	private ScheduledFuture<?>				timer;

	private TorrentAttribute				ta_networks;
	private TorrentAttribute				ta_peer_sources;
	
	private ListenerBundle					listener					= new ListenerBundle();

	private Map<Download, TrackedTorrent>	trackedTorrents				= new HashMap<>();
	
	private Queue<TrackedTorrent>			scrapeQueue					= new DelayQueue<>();
	private Queue<TrackedTorrent>			announceQueue				= new DelayQueue<>();

	private AsyncDispatcher	dispatcher = new AsyncDispatcher();
	
	protected Tracker (MlDHTPlugin plugin) {
		this.plugin = plugin;
		ta_networks = plugin.getPluginInterface().getTorrentManager().getAttribute(
				TorrentAttribute.TA_NETWORKS);
		ta_peer_sources = plugin.getPluginInterface().getTorrentManager().getAttribute(
				TorrentAttribute.TA_PEER_SOURCES);

	}

	protected void start () {
		if (running) {
			return;
		}
		DHT.logInfo("Tracker: starting...");
		timer = plugin.executor.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run () {
				checkQueues();
			}
		}, 100 * 1000, TRACKER_UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
		plugin.getPluginInterface().getDownloadManager().addListener(listener);

		running = true;
	}

	protected void stop () {
		if (!running) {
			return;
		}
		DHT.logInfo("Tracker: stopping...");
		if (timer != null) {
			timer.cancel(false);
		}
		announceQueue.clear();
		synchronized( trackedTorrents ){
			trackedTorrents.clear();
		}
		plugin.getPluginInterface().getDownloadManager().removeListener(listener);
		Download[] downloads = plugin.getPluginInterface().getDownloadManager().getDownloads();
		for (Download dl : downloads) {
			listener.cleanup(dl);
		}

		running = false;
	}

	protected void announceDownload (final Download dl) {
		if (running) {
			if (dl.getTorrent() == null) {
				return;
			}

			if (dl.getTorrent().isPrivate()) {
				DHT.logDebug("Announce for [" + dl.getName()
						+ "] forbidden because Torrent is private.");
				return;
			}
			
			final long startTime = System.currentTimeMillis();

			final TrackedTorrent tor;
			final boolean scrapeOnly;
			
			synchronized( trackedTorrents ){
				tor = trackedTorrents.get(dl);
				if ( tor != null ) {
					if (tor.isAnnouncing()) {
						DHT.logDebug("Announce for ["
								+ dl.getName()
								+ "] was denied since there is already one running.");
						return;
					}
				}
				
				scrapeOnly = MlDHTPlugin.getEffectiveDownloadState( dl ) == Download.ST_QUEUED;

				DHT.logInfo("DHT Starting Announce for " + dl.getName() + ", scrape=" + scrapeOnly + ", seeds=" + !dl.isComplete(true));
												
				if (tor != null) {
					tor.setAnnouncing(true);
					tor.setLastAnnounceStart(startTime);
				}
								
				(scrapeOnly ? currentScrapes : currentAnnounces).add(dl);
			}
			
			new TaskListener() {
				Set<PeerAddressDBItem> items = new HashSet<>();
				ScrapeResponseHandler scrapeHandler = new ScrapeResponseHandler();
				final boolean[] done = {false};
				final boolean[] interiming = {false};
				
				boolean allFinished = false;
				final TimerEvent	timeoutEvent;
				
				BiConsumer<KBucketEntry,PeerAddressDBItem> announceHandler =
					(scrapeOnly||tor==null||tor.getAnnounceCount()>1)?
					null:
					new BiConsumer<KBucketEntry,PeerAddressDBItem>()
					{
						private Set<PeerAddressDBItem> interim_items = new HashSet<>();
						
						@Override
						public void
						accept(
							KBucketEntry 		entry,
							PeerAddressDBItem 	item )
						{
							synchronized( interiming ){
								
								if ( interim_items.size() >= 200 ){
									
									return;
								}
								
								interim_items.add( item );
							
								if ( !( interiming[0] || done[0] )) {
									
									interiming[0] = true;
									
									new AEThread2("mlDHT:interim" )
									{
										@Override
										public void
										run()
										{
											try {
												Thread.sleep(50);
											}catch( Throwable e ) {
												
											}
											synchronized( interiming ){
												
												if ( !done[0] ){
													
													interiming[0] = false;
													
													DHTAnnounceResult res = new DHTAnnounceResult( dl, interim_items, 0);
													
													dl.setAnnounceResult(res);
												}
											}
										}
									}.start();
								}
							}
						}
					};
				
				AtomicInteger pendingCount = new AtomicInteger();
				
				{ // initializer					
					byte[] hash = dl.getTorrent().getHash();
					
					for(DHTtype type : DHTtype.values())
					{
						DHT dht = plugin.getDHT(type);
						PeerLookupTask lookupTask = dht.createPeerLookup( hash );
						if (lookupTask != null) {
							pendingCount.incrementAndGet();
							lookupTask.setScrapeHandler(scrapeHandler);
							if(announceHandler != null)
								lookupTask.setResultHandler(announceHandler);
							lookupTask.setNoAnnounce(scrapeOnly);
							lookupTask.addListener(this);
							lookupTask.setInfo(dl.getName());
							lookupTask.setNoSeeds(dl.isComplete(true));
							dht.getTaskManager().addTask(lookupTask);
						}
					}

					if ( pendingCount.get() == 0 ){
						
							// no tasks created, we need to trigger completion otherwise the torrent will be 'stuck'
						
						allFinished( false );
						
						timeoutEvent = null;
					}else{
						
							// reports of announces getting stuck when network goes down - added timeout
							// hack
						
						timeoutEvent = 
							SimpleTimer.addEvent( 
								"mlDHT:tt", 
								SystemTime.getOffsetTime( 15*60*1000 ),
								(ev)->{
									DHT.logInfo("DHT Announce timeout for " + dl.getName());
									allFinished( false );
								});
					}
				}
				
				@Override
				public void finished(Task t) {
					DHT.logDebug("DHT Task done: " + t.getClass().getSimpleName());
					synchronized( interiming ) {
						done[0] = true;
					}
					if (t instanceof PeerLookupTask) {
						PeerLookupTask peerLookup = (PeerLookupTask) t;
						synchronized (items)
						{
							items.addAll(peerLookup.getReturnedItems());
						}
						
							// no announce for metadata downloads
						if ( !dl.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
								// if we're not just scraping the torrent... send announces
							if(!scrapeOnly){
								t.getRPC().getDHT().announce(peerLookup, dl.isComplete(true),plugin.getPluginInterface().getPluginconfig().getUnsafeIntParameter("TCP.Listen.Port"));
							}
						}
						
						if(pendingCount.decrementAndGet() > 0)
							return;
						allFinished( true );
					}
				}
				
				private void 
				allFinished( boolean didSomething )
				{
					synchronized( interiming ){
						if ( allFinished ){
							return;
						}
						allFinished = true;
						if ( timeoutEvent != null ){
							timeoutEvent.cancel();
						}
					}
					scrapeHandler.process();
					
					synchronized( trackedTorrents ){
						currentAnnounces.remove(dl);
						currentScrapes.remove(dl);
						
						if (tor != null) {
							tor.setAnnouncing(false);
						}
					}
					
					// schedule the next announce (will be ignored if there is one pending)
					scheduleTorrent(dl, false);
					
					if (!scrapeOnly ){ // parg: removed this as hopefully multiple announce sources are handled better these days... && items.size() > 0) {
						
						if ( items.size() > 0 || didSomething ){
							DHTAnnounceResult res = new DHTAnnounceResult(dl, items, tor != null ? (int) tor.getDelay(TimeUnit.SECONDS) : 0);
							res.setScrapePeers(scrapeHandler.getScrapedPeers());
							res.setScrapeSeeds(scrapeHandler.getScrapedSeeds());
							
							dl.setAnnounceResult(res);
						}
					}
					
					if(scrapeOnly && (scrapeHandler.getScrapedPeers() > 0 || scrapeHandler.getScrapedSeeds() > 0))
					{
						DHTScrapeResult res = new DHTScrapeResult(dl, scrapeHandler.getScrapedSeeds(), scrapeHandler.getScrapedPeers());
						res.setScrapeStartTime(startTime);
						dl.setScrapeResult(res);
					}
					
					DHT.logInfo("DHT Announce finished for " + dl.getName()
							+ " found " + items.size() + " Peers.");
				}
				
				
			};
		}
	}

	private void scheduleTorrent (final Download dl, boolean shortDelay) {
		if (!running) {
			return;
		}
		
		TrackedTorrent t;
		
		synchronized( trackedTorrents ){
			
			t = trackedTorrents.get(dl);
		}
		
		if ( t != null ){
						
			Queue<TrackedTorrent> targetQueue;
			int delay;
			
			if(t.scrapeOnly())
			{
				targetQueue = scrapeQueue;
				announceQueue.remove(t);
				delay = shortDelay ? (SHORT_DELAY + random.nextInt(SHORT_DELAY)) : (MIN_SCRAPE_INTERVAL + random.nextInt(MAX_SCRAPE_INTERVAL));
			} else {
				targetQueue = announceQueue;
				scrapeQueue.remove(t);
				
				if ( shortDelay ){
					
					if ( dl.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
						
							// wanna kick this in ASAP

						delay = 0;
						
					}else{
						
						if ( t.getAnnounceCount() == 0 && !dl.isComplete(true)){
						
								// first announce for an incomplete download, let's get some urgency into this!
							
							delay = VERY_SHORT_DELAY + random.nextInt(VERY_SHORT_DELAY);
						}else{
						
							delay = SHORT_DELAY + random.nextInt(SHORT_DELAY);
						}
					}
				}else{
					
					if ( dl.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
						
						delay = METADATA_ANNOUNCE_DELAY;
						
					}else{
					
						delay = MIN_ANNOUNCE_INTERVAL + random.nextInt(MAX_ANNOUNCE_INTERVAL);
					}
				}
			}
			
			if (targetQueue.contains(t))
			{
				if (shortDelay)
					targetQueue.remove(t);
				else
					return; // still queued, no need to announce
			}
			t.setDelay(delay);

			DHT.logInfo("Tracker: scheduled "+(t.scrapeOnly() ? "scrape" : "announce")+" in "
					+ t.getDelay(TimeUnit.SECONDS) + "sec for: " + dl.getName());
			
			if ( delay == 0 ){
				
				dispatcher.dispatch(
						new AERunnable()
						{
							@Override
							public void
							runSupport()
							{
								announceDownload(dl);
							}
						});
			}else{
				
				targetQueue.add(t);
			}
		}
	}

	private void
	checkQueues()
	{
		dispatcher.dispatch(
			new AERunnable()
			{
				@Override
				public void
				runSupport()
				{
					checkQueuesSupport();
				}
			});
	}
	
	private void checkQueuesSupport () {
		if (!running) {
			return;
		}

		while ( true ){
			synchronized( trackedTorrents ){
				if ( currentAnnounces.size() >= MAX_CONCURRENT_ANNOUNCES ){
					break;
				}
			}
			
			TrackedTorrent t = announceQueue.poll();
			
			if ( t == null ){
				break;
			}
		
			Download dl = t.getDownload();
			if ( t.isAnnouncing()){
				scheduleTorrent(dl, false);
			}else{
				announceDownload(dl);
			}
		}
		
		while ( true ){
			synchronized( trackedTorrents ){
				if ( currentScrapes.size() >= MAX_CONCURRENT_SCRAPES ){
					break;
				}
			}
			
			TrackedTorrent t = scrapeQueue.poll();
			
			if ( t == null ){
				break;
			}
		
			Download dl = t.getDownload();
			if ( t.isAnnouncing()){
				scheduleTorrent(dl, false);
			}else{
				announceDownload(dl);
			}
		}
	}

	private void checkDownload (Download dl) {
		if (!running || dl.getTorrent() == null || dl.getTorrent().isPrivate())
			return;
		
		String[] sources = dl.getListAttribute(ta_peer_sources);
		String[] networks = dl.getListAttribute(ta_networks);

		boolean ok = false;

		if (networks != null) {
			for (int i = 0; i < networks.length; i++) {
				if (networks[i].equalsIgnoreCase("Public")) {
					ok = true;
					break;
				}
			}
		}

		if (!ok) {
			removeTrackedTorrent(dl, "Network is not public anymore");
			return;
		}

		ok = false;

		for (int i = 0; i < sources.length; i++) {
			if (sources[i].equalsIgnoreCase(PEER_SOURCE_NAME)) {
				ok = true;
				break;
			}
		}

		if (!ok) {
			removeTrackedTorrent(dl, "Peer source was disabled");
			return;
		}
		
		final TrackedTorrent tor;
		
		synchronized( trackedTorrents ){
			
			tor = trackedTorrents.get(dl);
		}

		int state = MlDHTPlugin.getEffectiveDownloadState( dl );
		
		if (state == Download.ST_DOWNLOADING || state == Download.ST_SEEDING) {

			 if ( !dl.getFlag( Download.FLAG_METADATA_DOWNLOAD )){
				//only act as backup tracker
				if (plugin.getPluginInterface().getPluginconfig().getPluginBooleanParameter("backupOnly")) {
					DownloadAnnounceResult result = dl.getLastAnnounceResult();
	
					if (result == null || result.getResponseType() == DownloadAnnounceResult.RT_ERROR) {
						addTrackedTorrent(dl, "BackupTracker");
					} else {
						removeTrackedTorrent(dl, "BackupTracker no longer needed");
					}
	
					return;
				}
			 }

			addTrackedTorrent(dl, "Normal");

		} else if(state == Download.ST_QUEUED) {
			
			// handle scrapes if regular scrapes failed or it's only dht-tracked
			DownloadScrapeResult scrResult = dl.getLastScrapeResult();
			
			if(	scrResult.getResponseType() == DownloadScrapeResult.RT_ERROR || (tor != null && scrResult.getScrapeStartTime() == tor.getLastAnnounceStart())) {
				// faulty states or our own scrape => let's scrape again
				addTrackedTorrent(dl, "BackupScraper");
			} else {
				// scrape seems fine => no need for DHT scrape
				removeTrackedTorrent(dl, "BackupScraper no longer needed");
			}

		} else if ( state == Download.ST_STOPPED || state == Download.ST_ERROR ){
			removeTrackedTorrent(dl, "Has stopped Downloading/Seeding (state=" + state + ")");
		}


	}

	private void addTrackedTorrent (Download dl, String reason) {
		synchronized( trackedTorrents ){
			if (trackedTorrents.containsKey(dl)) {
				return;
			}
			
			DHT.logInfo("Tracker: starting to track Torrent reason: " + reason
						+ ", Torrent; " + dl.getName());
			trackedTorrents.put(dl, new TrackedTorrent(dl));
		}
		
		scheduleTorrent(dl, true);
	}

	private void removeTrackedTorrent (Download dl, String reason) {
		synchronized( trackedTorrents ){
			TrackedTorrent tracked = trackedTorrents.remove(dl);
			if ( tracked != null ) {
				DHT.logInfo("Tracker: stop tracking of Torrent reason: " + reason
						+ ", Torrent; " + dl.getName());
							
				announceQueue.remove(tracked);
				scrapeQueue.remove(tracked);
			}
		}
	}

	public List<TrackedTorrent> getTrackedTorrentList () {
		synchronized( trackedTorrents ){
			return new ArrayList<>(trackedTorrents.values());
		}
	}


	

	private class ListenerBundle implements DownloadListener,
	DownloadAttributeListener, DownloadTrackerListener,
	DownloadManagerListener, DownloadManagerStateAttributeListener {
		
		public void cleanup(Download download) {
			download.removeAttributeListener(this, ta_networks,	DownloadAttributeListener.WRITTEN);
			download.removeAttributeListener(this, ta_peer_sources,	DownloadAttributeListener.WRITTEN);
			
			PluginCoreUtils.unwrap( download ).getDownloadState().removeListener( this, DownloadManagerState.AT_PLUGIN_OPTIONS, DownloadManagerStateAttributeListener.WRITTEN );

			download.removeListener(this);
			download.removeTrackerListener(this);
		}
		
		
		//---------------------[DownloadListener]---------------------------------
		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadListener#positionChanged(com.biglybt.pif.download.Download, int, int)
		 */
		@Override
		public void positionChanged (Download download, int oldPosition,
		                             int newPosition) {
		}

		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadListener#stateChanged(com.biglybt.pif.download.Download, int, int)
		 */
		@Override
		public void stateChanged (Download download, int old_state, int new_state) {
			checkDownload(download);
		}

		//---------------------[DownloadAttributeListener]---------------------------------

		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadAttributeListener#attributeEventOccurred(com.biglybt.pif.download.Download, com.biglybt.pif.torrent.TorrentAttribute, int)
		 */
		@Override
		public void attributeEventOccurred (Download download,
		                                    TorrentAttribute attribute, int event_type) {
			if (event_type == DownloadAttributeListener.WRITTEN
					&& (attribute == ta_networks || attribute == ta_peer_sources)) {
				checkDownload(download);
			}

		}
		
		@Override
		public void attributeEventOccurred(DownloadManager download, String attribute, int event_type){
			checkDownload( PluginCoreUtils.wrap( download ));
		}

		//---------------------[DownloadTrackerListener]---------------------------------
		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadTrackerListener#announceResult(com.biglybt.pif.download.DownloadAnnounceResult)
		 */
		@Override
		public void announceResult (DownloadAnnounceResult result) {
			checkDownload(result.getDownload());
		}

		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadTrackerListener#scrapeResult(com.biglybt.pif.download.DownloadScrapeResult)
		 */
		@Override
		public void scrapeResult (DownloadScrapeResult result) {
			checkDownload(result.getDownload());
		}

		//---------------------[DownloadTrackerListener]---------------------------------
		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadManagerListener#downloadAdded(com.biglybt.pif.download.Download)
		 */
		@Override
		public void downloadAdded (Download download) {
			download.addAttributeListener(this, ta_networks, DownloadAttributeListener.WRITTEN);
			download.addAttributeListener(this, ta_peer_sources,DownloadAttributeListener.WRITTEN);
			
			PluginCoreUtils.unwrap( download ).getDownloadState().addListener( this, DownloadManagerState.AT_PLUGIN_OPTIONS, DownloadManagerStateAttributeListener.WRITTEN );
			
			download.addListener(this);
			download.addTrackerListener(this);
			checkDownload(download);
		}

		/* (non-Javadoc)
		 * @see com.biglybt.pif.download.DownloadManagerListener#downloadRemoved(com.biglybt.pif.download.Download)
		 */
		@Override
		public void downloadRemoved (Download download) {
			cleanup(download);
			removeTrackedTorrent(download, "Download was removed");
		}
		
	}
	
}
