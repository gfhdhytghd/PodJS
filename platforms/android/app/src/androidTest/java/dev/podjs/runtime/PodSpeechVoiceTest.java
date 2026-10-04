package dev.podjs.runtime;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PodSpeechVoiceTest {
    private static final class Engine extends TextToSpeech {
        Set<Voice> voices=new LinkedHashSet<>();Voice selected;Locale language;
        Set<String> available=new HashSet<>();boolean rejectVoice;
        Engine(Context context){super(context,status->{});}
        @Override public Set<Voice> getVoices(){return voices;}
        @Override public int setVoice(Voice voice){if(rejectVoice)return ERROR;selected=voice;return SUCCESS;}
        @Override public int isLanguageAvailable(Locale locale){return available.contains(locale.getLanguage())?LANG_AVAILABLE:LANG_NOT_SUPPORTED;}
        @Override public int setLanguage(Locale locale){language=locale;return isLanguageAvailable(locale);}
        @Override public int speak(CharSequence text,int mode,android.os.Bundle args,String id){throw new AssertionError("voice selection must not synthesize");}
    }
    private static Voice voice(String name,String locale,boolean network){return new Voice(name,Locale.forLanguageTag(locale),Voice.QUALITY_NORMAL,Voice.LATENCY_NORMAL,network,Collections.emptySet());}
    private static JSONArray preferences(){return new JSONArray().put("zh-CN").put("zh").put("en");}
    @Test public void offlineChineseWinsAndNetworkOnlyChineseFallsBackToOfflineEnglish() throws Exception {
        Engine engine=new Engine(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            engine.voices.add(voice("online-zh","zh-CN",true));engine.voices.add(voice("offline-en","en-US",false));engine.voices.add(voice("offline-zh","zh-CN",false));
            assertEquals("zh",PodMediaServices.selectLocalVoice(engine,preferences()).getLanguage());assertEquals("offline-zh",engine.selected.getName());
            engine.voices.remove(engine.selected);assertEquals("en",PodMediaServices.selectLocalVoice(engine,preferences()).getLanguage());assertEquals("offline-en",engine.selected.getName());
        }finally{engine.shutdown();}
    }
    @Test public void rejectedVoiceFallsBackToSupportedLanguageAndMissingVoicesFail() throws Exception {
        Engine engine=new Engine(InstrumentationRegistry.getInstrumentation().getTargetContext());
        try {
            engine.voices.add(voice("offline-zh","zh-CN",false));engine.rejectVoice=true;engine.available.add("en");
            assertEquals("en",PodMediaServices.selectLocalVoice(engine,preferences()).getLanguage());assertEquals("en",engine.language.getLanguage());
            engine.available.clear();try{PodMediaServices.selectLocalVoice(engine,preferences());fail("missing voice accepted");}catch(IllegalStateException expected){assertEquals("设备没有可用的本地 TTS 语音",expected.getMessage());}
        }finally{engine.shutdown();}
    }
}
