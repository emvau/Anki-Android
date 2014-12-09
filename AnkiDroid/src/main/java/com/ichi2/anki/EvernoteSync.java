package com.ichi2.anki;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;

import com.ichi2.anki.exception.ConfirmModSchemaException;
import com.ichi2.libanki.Collection;
import com.ichi2.libanki.Note;
import com.ichi2.themes.StyledProgressDialog;
import com.evernote.client.android.AsyncNoteStoreClient;
import com.evernote.client.android.EvernoteSession;
import com.evernote.client.android.InvalidAuthenticationException;
import com.evernote.client.android.OnClientCallback;
import com.evernote.edam.error.EDAMNotFoundException;
import com.evernote.edam.error.EDAMSystemException;
import com.evernote.edam.error.EDAMUserException;
import com.evernote.edam.notestore.NoteFilter;
import com.evernote.edam.notestore.NoteMetadata;
import com.evernote.edam.notestore.NotesMetadataList;
import com.evernote.edam.notestore.NotesMetadataResultSpec;
import com.evernote.thrift.TException;
import com.evernote.thrift.transport.TTransportException;

public class EvernoteSync {

	private static EvernoteSync singletonInstance;
	private static final String CONSUMER_KEY = "matthiasv-3883" ;
	private static final String CONSUMER_SECRET = "a944056d8952611c" ;
	private static final EvernoteSession.EvernoteService EVERNOTE_SERVICE = EvernoteSession.EvernoteService.PRODUCTION;
	protected static EvernoteSession eSession;
	private Context context;
	private long lastSync;
	private SharedPreferences sharedPrefs;
	private Collection aCol;
	private String eSearchString;
	protected String eDeckName;
	private int eTotalNotes = 0;
	private AsyncNoteStoreClient eNoteStore;
	private StyledProgressDialog progressDialog;

	private EvernoteSync(Context ctx) throws Exception {
		this.context = ctx;
		if (!EvernoteAppInstalled("com.evernote")) {
			throw new Exception("Evernote app is not installed");
		} 
		eSession = EvernoteSession.getInstance(context , CONSUMER_KEY , CONSUMER_SECRET , EVERNOTE_SERVICE, false );
	}
	
	public static EvernoteSync getInstance(Context ctx) {
		if (null == singletonInstance) {
			try {
				singletonInstance = new EvernoteSync(ctx);
			} catch (Exception e) {
				//Log.e(AnkiDroidApp.TAG, e.getMessage());
				e.printStackTrace();
			}
		}
		return singletonInstance;
	}

	public void login() {
		eSession = EvernoteSession.getInstance(this.context , CONSUMER_KEY , CONSUMER_SECRET , EVERNOTE_SERVICE, false );
		eSession.authenticate(this.context);
	}

	public void logout() {
		try {
			eSession = EvernoteSession.getInstance(this.context, CONSUMER_KEY , CONSUMER_SECRET , EVERNOTE_SERVICE, false );
			eSession.logOut(this.context);
		} catch (InvalidAuthenticationException e) {
			Log.e(AnkiDroidApp.TAG, "Evernote: Tried to call logout with not logged in", e);
		}
	}

	public void sync(){
		
		if (!eSession.isLoggedIn()) {
			login();
			return;
		};
		
		sharedPrefs = context.getSharedPreferences(AnkiDroidApp.SHARED_PREFS_NAME, Context.MODE_PRIVATE);
		lastSync = sharedPrefs.getLong("evernoteSync_LastSync", new Date().getTime());
		eSearchString = sharedPrefs.getString("evernoteSync_SearchString","");
		aCol = AnkiDroidApp.getCol();
		
		Map <String,String> eSearches = new HashMap<String,String>();
		
		for(String keyValue : eSearchString.split(" *; *")) {
		   String[] pairs = keyValue.split(" *= *", 2);
		   eSearches.put(pairs[0], pairs.length == 1 ? "" : pairs[1]);
		}

		Iterator<Entry<String, String>> iterator = eSearches.entrySet().iterator();
		while (iterator.hasNext()) {
		    Map.Entry<String,String> pairs = (Map.Entry<String,String>)iterator.next();
	    	new syncDeck().execute(pairs.getKey(), pairs.getValue());
		}
		Log.i(AnkiDroidApp.TAG, "Evernote: Sync finished");
		setLastSyncTime();
	}
	
	private class syncDeck extends AsyncTask<String, Void, String> {
		
		String deckName;
		String searchString;
	    
	    protected String doInBackground(String... params) {
	    	
	    	deckName = params[0];
	    	searchString = params[1];

            try {
                prepareDeck(deckName);
            } catch (ConfirmModSchemaException e) {
                e.printStackTrace();
            }

            NoteFilter filter = new NoteFilter();
			filter.setAscending(true);
			filter.setWords(searchString);
		
			int maxNotes = 999;
			int offset = 0;
			NotesMetadataResultSpec resultSpec = new NotesMetadataResultSpec();
			resultSpec.setIncludeTitle(true);
			resultSpec.setIncludeAttributes(true);
			resultSpec.setIncludeUpdated(true);
			try {
				eNoteStore = eSession.getClientFactory().createNoteStoreClient();
				eNoteStore.findNotesMetadata(filter, offset, maxNotes,resultSpec, new OnClientCallback<NotesMetadataList>() {
	
					@Override
					public void onSuccess(NotesMetadataList data) {
						new syncNotesMetadataList().execute(data);
					}
	
					@Override
					public void onException(Exception exception) {
						Log.e(AnkiDroidApp.TAG, "Evernote: " + exception.getMessage());
					}
				});
	
	
			} catch (TTransportException exception) {
				Log.e(AnkiDroidApp.TAG, "Evernote: " + exception.getMessage());
			}
			
	        return deckName;
	    }
	    
	    protected void onPostExecute(String deckName) {
	    	Log.i(AnkiDroidApp.TAG, "Evernote: Sync of " + deckName + " finished");
	    }
	    
	    private class syncNotesMetadataList extends AsyncTask<NotesMetadataList, Integer, String> {
			
			@Override
	        public void onPreExecute() {
	           //progressDialog = StyledProgressDialog.show(context, "Evernote Sync","Preparing Sync", true);
	        }
			
			@Override
	        public void onProgressUpdate(Integer... counter) {
	        	//progressDialog.setMessage("Syncing Evernote note " + counter[0].toString() + " of " + eTotalNotes + "...");
	         }
			
			
			@Override
			protected String doInBackground(NotesMetadataList... params) {
				eTotalNotes = ((NotesMetadataList) params[0]).getTotalNotes();
				Integer counter = 0;
				Log.i(AnkiDroidApp.TAG, "Evernote: count of found notes is " + eTotalNotes);
				
				//get a list of all ankiNotes
				ArrayList<ankiNote> aNotes = new ArrayList<ankiNote>();
				long did = 0L;
				try {
					did = aCol.getDecks().byName(deckName).getLong("id");
				} catch (JSONException e) {
					e.printStackTrace();
				}
				long[] aCards = aCol.getDecks().cids(did);
				for (long i : aCards) {
					long Nid = aCol.getCard(i).getNid();
					aNotes.add(new ankiNote(aCol,Nid));
				}
				
				// get the list of all everNotes
				List<NoteMetadata> eNotes;
				eNotes = params[0].getNotes();
			
				//sync existing notes
				for (Iterator<NoteMetadata> everIter = eNotes.iterator();everIter.hasNext();) {
					NoteMetadata eNote = everIter.next();
					this.publishProgress(counter++);
					String everGuid = eNote.getGuid();
					
					//check if there's an aNote for the eNote
					for (Iterator<ankiNote> ankiIter  = aNotes.iterator();ankiIter.hasNext();) {
						ankiNote aNote = ankiIter.next();
						if (aNote.getGuid().equals(everGuid)) {
							if(lastSync < eNote.getUpdated()){
								String everTitle = eNote.getTitle();
								aNote.setTitle(everTitle);
								aNote.setGuid(everGuid);
								aNote.flush();
								Log.i(AnkiDroidApp.TAG, "Evernote: anki note of " + everTitle + " got updated");
							}
							ankiIter.remove();
							everIter.remove();
						}
					}
				}
				
				//delete old aNotes
				
				long[] to_delete = new long[aNotes.size()];
				for (int i = 0; i < aNotes.size(); i++)
					try {
						if (aNotes.get(i).getMid() == aCol.getModels().byName("Evernote").getLong("id")) {
							to_delete[i] = aNotes.get(i).getId();
						}
					} catch (JSONException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				aCol.remNotes(to_delete);
				
				//create new aNotes
				
				JSONObject model = aCol.getModels().byName("Evernote");
				for(NoteMetadata eNote : eNotes) {
					ankiNote aNote = new ankiNote(aCol,deckName, model);
					aNote.setTitle(eNote.getTitle());
					aNote.setGuid(eNote.getGuid());
					aCol.addNote(aNote);
				}
				return null;
			}

			@Override
			protected void onPostExecute(String result) {
				//progressDialog.dismiss();
			}

		}
	    
	}

	

	private void setLastSyncTime() {
		Date date = new Date(System.currentTimeMillis());
		long millis = date.getTime();
		SharedPreferences.Editor editor = sharedPrefs.edit();
		editor.putLong("evernoteSync_LastSync" , millis);
		editor.commit();
	}
	
	public static class updateUsername extends AsyncTask<Void, String, String> {
		private Context mContext;
		public updateUsername(Context ctx){
			mContext = ctx;
		}

		@Override
		protected String doInBackground(Void... params) {
			String username = "unknown";
			try {
				username = eSession.getClientFactory().createUserStoreClient().getClient().getUser(eSession.getAuthToken()).getName();
			} catch (IllegalStateException e) {
				e.printStackTrace();
			} catch (TTransportException e) {
				e.printStackTrace();
			} catch (EDAMUserException e) {
				e.printStackTrace();
			} catch (EDAMSystemException e) {
				e.printStackTrace();
			} catch (TException e) {
				e.printStackTrace();
			}
			return username;
		}

		@Override
		protected void onPostExecute(String username) {
			SharedPreferences prefs = mContext.getSharedPreferences(AnkiDroidApp.SHARED_PREFS_NAME, Context.MODE_PRIVATE);
			SharedPreferences.Editor editor = prefs.edit();
			editor.putString("evernoteSync_Username", username);
			editor.commit();
		}
	}

	public void prepareDeck(String deckName) throws ConfirmModSchemaException {
		if (aCol.getDecks().byName(deckName)== null){
			aCol.getDecks().id(deckName, true);
		}
		if (aCol.getModels().byName("Evernote") == null){
			JSONObject m = aCol.getModels().newModel("Evernote");
			aCol.getModels().addField(m, aCol.getModels().newField("title"));
			aCol.getModels().addField(m, aCol.getModels().newField("guid"));			
			JSONObject t = aCol.getModels().newTemplate("Card");
			try {
				t.put("qfmt", "{{title}}");
				t.put("afmt", "");
			} catch (JSONException e) {
				throw new RuntimeException(e);
			}
			aCol.getModels().addTemplate(m, t);
			aCol.getModels().add(m);
		}
	}
	
	private boolean EvernoteAppInstalled(String uri)
    {
        PackageManager pm = context.getPackageManager();
        boolean app_installed = false;
        try
        {
               pm.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
               app_installed = true;
        }
        catch (PackageManager.NameNotFoundException e)
        {
               app_installed = false;
        }
        return app_installed ;
    }    
        
    private class ankiNote extends Note
    {

        //get existing aNote
        public ankiNote(Collection aCol, long aNoteId)
        {
        	super(aCol, aNoteId);
        }
              
        //create new aNote
        public ankiNote(Collection aCol, String deckName, JSONObject model) {
        	super(aCol, model);
        	
        	long aNoteDid = 0;
    		
    		try {
    			aNoteDid = aCol.getDecks().byName(deckName).getLong("id");
    		} catch (JSONException e) {
    			e.printStackTrace();
    		}
    		try {
    			this.model().put("did", aNoteDid);
    		} catch (JSONException e) {
    			e.printStackTrace();
    		}
        }

        public String getGuid() { 
        	
			String[] Nfields = this.getFields();
			String guid = Nfields[1];
        	return guid; }
        
    	public void setTitle(String title) {
    		this.setitem("title", title);
    	}
    	
    	public void setGuid(String guid) {
    		this.setitem("guid", guid);
    	}	        
    }
}