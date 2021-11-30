// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;

import org.json.JSONException;
import org.json.JSONObject;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_MIC = 4;

    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_CODE = 0;

    private static final String[] permissions = {
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE
    };

    private static final int REQUEST_CODE_FILE = 77;
    private static final int REQUEST_CODE_FOLDER = 88;

    private static final int TRANSCRIPT_PLACEHOLDER_LENGTH = 12;

    private static final String MIME_TYPE_AUDIO = "audio/";

    private Intent intent;
    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private StringBuilder transcript;
    private TextView resultView;
    private TextView tooltipView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        tooltipView = findViewById(R.id.tooltip_text);
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);
        transcript = new StringBuilder("Transcript:\n");

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_folder).setOnClickListener(view -> recognizeFolder());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        findViewById(R.id.clear_transcript).setOnClickListener(view -> clearTranscript());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);

        // Check if user has given permission to record audio, init the model after permission is granted
        List<String> permissionsToRequest = new ArrayList<String>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        if (permissionsToRequest.size() > 0) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        } else {
            initModel();
        }
    }

    private void initModel() {
        StorageService.unpack(this, "model-en-us", "model",
                (model) -> {
                    this.model = model;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        try {
            JSONObject json = new JSONObject(hypothesis);
            transcript.append(json.getString("text"));
            transcript.append("\n");
        } catch (JSONException je) {
            setErrorState(je.getMessage());
        }
        // resultView.append(hypothesis + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        // resultView.append(hypothesis + "\n");
        try {
            JSONObject json = new JSONObject(hypothesis);
            transcript.append(json.getString("text"));
            transcript.append("\n");
        } catch (JSONException je) {
            setErrorState(je.getMessage());
        }
        resultView.append(transcript);
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }

        if (intent != null) {
            intent = null;
        }

        transcript.setLength(TRANSCRIPT_PLACEHOLDER_LENGTH);
    }

    @Override
    public void onPartialResult(String hypothesis) {
        //resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                tooltipView.setText(R.string.preparing);
                tooltipView.setMovementMethod(new ScrollingMovementMethod());
                resultView.setText("");
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_folder).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.clear_transcript).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                tooltipView.setText(R.string.ready);
                resultView.setText("");
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_folder).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.clear_transcript).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                tooltipView.setText(R.string.ready);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_folder).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.clear_transcript).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                tooltipView.setText(getString(R.string.say_something));
                resultView.setText("");
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_folder).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.clear_transcript).setEnabled(false);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        tooltipView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_folder).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
        findViewById(R.id.clear_transcript).setEnabled(false);
    }

    private void recognizeFile() {

        if (speechStreamService != null) {
            speechStreamService.stop();
            speechStreamService = null;
        }

        intent = null;

        // setUiState(STATE_READY);
        transcript.setLength(TRANSCRIPT_PLACEHOLDER_LENGTH);

        if (intent == null) {
            // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file browser.
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

            // Filter to only show results that can be "opened", such as a
            // file (as opposed to a list of contacts or timezones)
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            // Allow the selection of multiple files
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            // Filter to show only images, using the image MIME data type.
            // If one wanted to search for ogg vorbis files, the type would be "audio/ogg".
            // To search for all documents available via installed storage providers,
            // it would be "*/*".
            intent.setType("audio/*");
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_FILE);
        } catch (Exception e) {
            setErrorState(e.getMessage());
        }
    }

    private void recognizeFolder() {
        if (speechStreamService != null) {
            speechStreamService.stop();
            speechStreamService = null;
        }
        intent = null;

        // setUiState(STATE_READY);
        transcript.setLength(TRANSCRIPT_PLACEHOLDER_LENGTH);

        if (intent == null) {
            // ACTION_OPEN_DOCUMENT is the intent to choose a file via the system's file browser.
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }

        try {
            startActivityForResult(intent, REQUEST_CODE_FOLDER);
        } catch (Exception e) {
            setErrorState(e.getMessage());
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode,
                                 Intent resultData) {

        // The ACTION_OPEN_DOCUMENT intent was sent with the request code
        // READ_REQUEST_CODE. If the request code seen here doesn't match, it's the
        // response to some other intent, and the code below shouldn't run at all.
        if (resultCode != Activity.RESULT_OK) {
            return;
        }

        if (resultData == null) {
            return;
        }

        ClipData clipData = null;
        List<Uri> folderData = new ArrayList<>();
        int numClips = -1;
        boolean isFile = false;
        // The document selected by the user won't be returned in the intent.
        // Instead, a URI to that document will be contained in the return intent
        // provided to this method as a parameter.
        // Pull that URI using resultData.getData() or
        // resultData.getClipData() for multiple files
        switch (requestCode) {
            case REQUEST_CODE_FILE:
                clipData = resultData.getClipData();
                if (clipData != null) {
                    numClips = clipData.getItemCount();
                } else {
                    numClips = 1;
                }
                isFile = true;
                break;
            case REQUEST_CODE_FOLDER:
                folderData = readFiles(getApplicationContext(), resultData);
                numClips = folderData.size();
                break;
            default:
                return;
        }

        try {
            for (int i = 0; i < numClips; i++) {
                Uri uri;
                if (numClips == 1) {
                    uri = resultData.getData();
                } else if (isFile) {
                    uri = clipData.getItemAt(i).getUri();
                } else {
                    uri = folderData.get(i);
                }
                MediaExtractor mex = new MediaExtractor();
                try {
                    mex.setDataSource(getApplicationContext(), uri, null);// the adresss location of the sound on sdcard.
                } catch (IOException e) {
                    setErrorState(e.getMessage());
                }

                MediaFormat mf = mex.getTrackFormat(0);

                int sampleRate = mf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                Recognizer rec = new Recognizer(model, (float) sampleRate);

                InputStream ais = getContentResolver().openInputStream(uri);

                if (ais.skip(44) != 44) throw new IOException("File too short");
                speechStreamService = new SpeechStreamService(rec, ais, (float) sampleRate);
                speechStreamService.start(this);
            }
        } catch (Exception e) {
            setErrorState(e.getMessage());
        }
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            transcript.setLength(TRANSCRIPT_PLACEHOLDER_LENGTH);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

    private void clearTranscript() {
        resultView.setText("");
    }

    private List<Uri> readFiles(Context context, Intent intent) {
        List<Uri> uriList = new ArrayList<>();

        // the uri returned by Intent.ACTION_OPEN_DOCUMENT_TREE
        Uri uriTree = intent.getData();
        // the uri from which we query the files
        Uri uriFolder = DocumentsContract.buildChildDocumentsUriUsingTree(uriTree, DocumentsContract.getTreeDocumentId(uriTree));

        Cursor cursor = null;
        try {
            // let's query the files
            cursor = context.getContentResolver().query(uriFolder,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null);

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    // build the uri for the file
                    Uri uriFile = DocumentsContract.buildDocumentUriUsingTree(uriTree, cursor.getString(0));
                    //add to the list

                    if (context.getContentResolver().getType(uriFile) != null
                            && context.getContentResolver().getType(uriFile).startsWith(MIME_TYPE_AUDIO)) {
                        uriList.add(uriFile);
                    }

                } while (cursor.moveToNext());
            }

        } catch (Exception e) {
            setErrorState(e.getMessage());
        } finally {
            if (cursor!=null) cursor.close();
        }

        //return the list
        return uriList;
    }
}
