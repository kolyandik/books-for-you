package ru.ulyanovsk.bookshelf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.widget.Toast;
import android.text.Html;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class MainActivity extends Activity {
    private WebView web;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private static final String NS="http://www.w3.org/2005/Atom";
    private static final int MAX_CATALOG=3*1024*1024;
    private static final int MAX_BOOK=70*1024*1024;
    private static final Set<String> HOSTS=new HashSet<>(Arrays.asList("http.flibusta.is", "flibusta.is", "www.flibusta.is", "flub.flibusta.is"));

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web=new WebView(this);
        web.setBackgroundColor(0xfff8f7f4);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); // only the packaged file:///android_asset interface
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                return !"file".equalsIgnoreCase(request.getUrl().getScheme());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }
        });
        web.addJavascriptInterface(new Bridge(),"Books");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onDestroy(){ super.onDestroy(); io.shutdownNow(); if(web!=null)web.destroy(); }
    @Override public void onBackPressed(){
        // The app is a single page with an overlay reader and book details.
        web.evaluateJavascript("window.mobileBack && window.mobileBack()",null);
    }

    private String base(){ return getPreferences(0).getString("host","https://http.flibusta.is"); }
    private void reply(String id, boolean ok, Object data){
        try {
            JSONObject output=new JSONObject(); output.put("ok",ok); output.put("data",data);
            String js="window.__bookshelfReply("+JSONObject.quote(id)+","+output.toString()+")";
            runOnUiThread(()-> { if(web!=null)web.evaluateJavascript(js,null); });
        } catch(Exception ignored){}
    }
    private void progress(String id,int percent){
        final String js="window.__bookshelfProgress("+JSONObject.quote(id)+","+percent+")";
        runOnUiThread(()-> {if(web!=null)web.evaluateJavascript(js,null);});
    }
    private void toast(String msg){runOnUiThread(()->Toast.makeText(this,msg,Toast.LENGTH_LONG).show());}

    private URL checked(String input, boolean catalog) throws Exception {
        if(input==null || input.length()>3000 || input.contains("\\") || input.contains("\n") || input.contains("\r"))throw new Exception("Неверный адрес источника");
        URL u=new URL(new URL(base()+"/"),input);
        if(!("https".equals(u.getProtocol())||"http".equals(u.getProtocol())) || !HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)))throw new Exception("Ссылка не принадлежит разрешённому книжному каталогу");
        if(u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=80 && u.getPort()!=443))throw new Exception("Недопустимая ссылка");
        String p=u.getPath();
        if(catalog && !(p.equals("/opds")||p.startsWith("/opds/")))throw new Exception("Ожидается адрес OPDS-каталога");
        if(!catalog && !Pattern.matches("(?i)/(?:b/\\d+/(?:fb2|epub|mobi|pdf|txt|rtf|html|djvu)|opds/[a-zA-Z0-9_/%.-]+)",p))throw new Exception("Источник не предоставил допустимую ссылку для загрузки");
        return u;
    }
    private HttpURLConnection connection(URL url,boolean catalog) throws Exception {
        for(int i=0;i<5;i++){
            URL validated=checked(url.toString(),catalog);
            HttpURLConnection c=(HttpURLConnection)validated.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(11000); c.setReadTimeout(19000);
            c.setRequestProperty("User-Agent","BookShelf-Ulyanovsk-Android/1.0");
            c.setRequestProperty("Accept",catalog?"application/atom+xml, application/xml, text/xml, */*;q=0.6":"*/*");
            int status=c.getResponseCode();
            if(status>=300 && status<400){
                String loc=c.getHeaderField("Location"); c.disconnect();
                if(loc==null)throw new Exception("Источник перенаправил запрос без адреса");
                url=new URL(validated,loc); continue;
            }
            if(status<200||status>=300){c.disconnect();throw new Exception("Источник ответил HTTP "+status+". Возможно, он недоступен.");}
            return c;
        }
        throw new Exception("Слишком много перенаправлений");
    }
    private byte[] bytes(InputStream in,int limit) throws Exception{
        try(InputStream stream=in; ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n,total=0;
            while((n=stream.read(buf))!=-1){total+=n;if(total>limit)throw new Exception("Слишком большой ответ источника");output.write(buf,0,n);}
            return output.toByteArray();
        }
    }
    private static String decode(byte[] bytes){
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException x){return new String(bytes,Charset.forName("windows-1251"));}
    }
    private static String clean(String source){
        if(source==null)return "";
        String s=Html.fromHtml(source,Html.FROM_HTML_MODE_LEGACY).toString();
        return s.replaceAll("[\\t ]+"," ").replaceAll("\\n{3,}","\n\n").trim();
    }
    private static String childText(Element node,String tag){
        NodeList list=node.getElementsByTagNameNS(NS,tag);
        if(list.getLength()>0)return list.item(0).getTextContent().trim();
        return "";
    }
    private static String fmt(String type,String url){
        String v=(type==null?"":type).toLowerCase(Locale.ROOT);
        if(v.contains("epub"))return "EPUB";
        if(v.contains("fb2")||v.contains("fictionbook"))return "FB2";
        if(v.contains("pdf"))return "PDF";
        if(v.contains("mobi"))return "MOBI";
        if(v.contains("text/plain")||v.contains("txt"))return "TXT";
        if(v.contains("rtf"))return "RTF";
        if(v.contains("djvu"))return "DJVU";
        if(v.contains("html"))return "HTML";
        Matcher m=Pattern.compile("(?i)/(fb2|epub|mobi|pdf|txt|rtf|html|djvu)(?:[?#]|$)").matcher(url);
        return m.find()?m.group(1).toUpperCase(Locale.ROOT):"";
    }
    private JSONObject parseCatalog(byte[] raw,String source) throws Exception{
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        Document doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(raw));
        Element root=doc.getDocumentElement();
        if(!NS.equals(root.getNamespaceURI())||!("feed".equals(root.getLocalName())||"entry".equals(root.getLocalName())))throw new Exception("Источник вернул не OPDS-каталог");
        JSONObject result=new JSONObject();result.put("title",childText(root,"title"));result.put("next",JSONObject.NULL);result.put("items",new JSONArray());
        URL sourceUrl=new URL(source);
        if("feed".equals(root.getLocalName())){
            NodeList rootNodes=root.getChildNodes();
            for(int i=0;i<rootNodes.getLength();i++){
                Node node=rootNodes.item(i);
                if(!(node instanceof Element))continue;
                Element link=(Element)node;
                if(!"link".equals(link.getLocalName()))continue;
                if("next".equals(link.getAttribute("rel"))){
                    try{result.put("next",checked(new URL(sourceUrl,link.getAttribute("href")).toString(),true).toString());}
                    catch(Exception ignored){}
                }
            }
        }
        NodeList entryNodes=root.getElementsByTagNameNS(NS,"entry");
        for(int i=0;i<entryNodes.getLength() && i<100;i++){
            Element entry=(Element)entryNodes.item(i);
            JSONObject book=new JSONObject();
            book.put("title",childText(entry,"title"));
            NodeList names=entry.getElementsByTagNameNS(NS,"name");
            StringBuilder authors=new StringBuilder();
            for(int k=0;k<names.getLength();k++){if(k>0)authors.append(", ");authors.append(names.item(k).getTextContent().trim());}
            book.put("author",authors.toString());
            String summary=childText(entry,"summary");if(summary.isEmpty())summary=childText(entry,"content");
            book.put("summary",clean(summary).substring(0,Math.min(4000,clean(summary).length())));
            JSONArray formats=new JSONArray();String nav="",site="",bookId="";
            NodeList links=entry.getElementsByTagNameNS(NS,"link");
            for(int j=0;j<links.getLength();j++){
                Element l=(Element)links.item(j);
                String href=l.getAttribute("href");if(href.isEmpty())continue;
                String rel=l.getAttribute("rel");String type=l.getAttribute("type");
                String absolute;
                try {absolute=new URL(sourceUrl,href).toString();checked(absolute,false);}
                catch(Exception e){
                    try{absolute=new URL(sourceUrl,href).toString();checked(absolute,true);}catch(Exception ignored){continue;}
                }
                Matcher matched=Pattern.compile("/b/(\\d+)").matcher(absolute);
                if(matched.find())bookId=matched.group(1);
                if("alternate".equals(rel)&&absolute.contains("/b/"))site=absolute;
                if(type.toLowerCase(Locale.ROOT).contains("application/atom+xml")||"subsection".equals(rel)||"collection".equals(rel)){
                    try{checked(absolute,true);nav=absolute;}catch(Exception ignored){}
                }
                String f=fmt(type,absolute);
                if((rel.contains("acquisition")||!f.isEmpty())&&!f.isEmpty()){
                    try{checked(absolute,false);JSONObject option=new JSONObject();option.put("format",f);option.put("url",absolute);formats.put(option);}
                    catch(Exception ignored){}
                }
            }
            book.put("formats",formats);book.put("navigation",nav);
            book.put("id",bookId);book.put("site",site);book.put("kind",(formats.length()>0||!bookId.isEmpty())?"book":"section");
            ((JSONArray)result.get("items")).put(book);
        }
        return result;
    }
    private String catalog(String path) throws Exception {
        URL url=checked(path,true);
        HttpURLConnection conn=connection(url,true);
        try {if(conn.getContentLengthLong()>MAX_CATALOG)throw new Exception("Каталог слишком большой");
            byte[] raw=bytes(conn.getInputStream(),MAX_CATALOG);
            if(raw.length<30)throw new Exception("Пустой ответ каталога");
            return parseCatalog(raw,conn.getURL().toString()).toString();
        } finally {conn.disconnect();}
    }
    private String filename(String title,String author,String format){
        String t=(title==null?"Книга":title).replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]"," ").trim();
        String a=(author==null?"":author).replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]"," ").trim();
        if(t.isEmpty())t="Книга";
        String ext;
        switch(format.toUpperCase(Locale.ROOT)){
            case "FB2":ext=".fb2.zip";break;case "EPUB":ext=".epub";break;case "PDF":ext=".pdf";break;
            case "MOBI":ext=".mobi";break;case "TXT":ext=".txt.zip";break;case "RTF":ext=".rtf.zip";break;
            case "HTML":ext=".html.zip";break;case "DJVU":ext=".djvu";break;default:ext=".bin";
        }
        String name=t+(a.isEmpty()?"":" — "+a);
        return name.substring(0,Math.min(105,name.length()))+ext;
    }
    private JSONObject download(String href,String title,String author,String format,String callbackId) throws Exception{
        URL url=checked(href,false);
        HttpURLConnection c=connection(url,false);
        Uri uri=null;
        try{
            String contentType=c.getContentType();
            if(contentType!=null && (contentType.toLowerCase(Locale.ROOT).contains("text/html")||contentType.toLowerCase(Locale.ROOT).contains("application/xhtml")))throw new Exception("Вместо книги источник вернул веб-страницу");
            long expected=c.getContentLengthLong();if(expected>MAX_BOOK)throw new Exception("Файл больше 70 МБ");
            String name=filename(title,author,format);
            String mime="application/octet-stream";
            switch(format.toUpperCase(Locale.ROOT)){
                case "EPUB":mime="application/epub+zip";break;
                case "PDF":mime="application/pdf";break;
                case "FB2":case "TXT":case "HTML":case "RTF":mime="application/zip";break;
            }
            ContentValues values=new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,name);
            values.put(MediaStore.Downloads.MIME_TYPE,mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Книжная полка");
            values.put(MediaStore.Downloads.IS_PENDING,1);
            uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(uri==null)throw new Exception("Не удалось создать файл в папке загрузок");
            long total=0;int previous=-1;
            byte[] start=new byte[64];int startSize=0;
            try(InputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(getContentResolver().openOutputStream(uri))){
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer))!=-1){
                    if(startSize<64){int cnt=Math.min(n,64-startSize);System.arraycopy(buffer,0,start,startSize,cnt);startSize+=cnt;}
                    total+=n;if(total>MAX_BOOK)throw new Exception("Файл превысил 70 МБ");
                    out.write(buffer,0,n);
                    if(expected>0){int percent=(int)Math.min(99,100*total/expected);if(percent>=previous+5){previous=percent;progress(callbackId,percent);}}
                }
            }
            String magic=new String(start,0,startSize,StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
            if(total<20||magic.startsWith("<!doctype")||magic.startsWith("<html")||magic.startsWith("<head"))throw new Exception("Получена HTML-страница или пустой файл вместо книги");
            if(format.equalsIgnoreCase("EPUB")&&!(start[0]=='P'&&start[1]=='K'))throw new Exception("Получен файл неожиданного формата");
            ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);
            getContentResolver().update(uri,done,null,null);
            progress(callbackId,100);
            JSONObject r=new JSONObject();r.put("name",name);r.put("uri",uri.toString());r.put("format",format);r.put("size",total);return r;
        }catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);throw e;}
        finally {c.disconnect();}
    }
    private JSONObject preview(String uriString,String format) throws Exception{
        Uri uri=Uri.parse(uriString);
        if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority()))throw new Exception("Можно открывать только скачанные книги");
        String f=format.toUpperCase(Locale.ROOT);
        if(!(f.equals("FB2")||f.equals("EPUB")||f.equals("TXT")||f.equals("HTML")))throw new Exception("Для этого формата используйте читалку на телефоне");
        String result;
        try(InputStream input=getContentResolver().openInputStream(uri)){
            if(input==null)throw new Exception("Файл удалён или недоступен");
            BufferedInputStream in=new BufferedInputStream(input);in.mark(8);
            byte[] magic=new byte[4];int n=in.read(magic);in.reset();
            if(n>=2&&magic[0]=='P'&&magic[1]=='K'){
                StringBuilder out=new StringBuilder();
                try(ZipInputStream zip=new ZipInputStream(in)){
                    ZipEntry entry;int count=0;
                    while((entry=zip.getNextEntry())!=null && count<35 && out.length()<350000){
                        String name=entry.getName().toLowerCase(Locale.ROOT);
                        if(name.endsWith(".fb2")||name.endsWith(".txt")||name.endsWith(".xhtml")||name.endsWith(".html")){
                            byte[] raw=bytesNoClose(zip,2*1024*1024);
                            out.append("\n\n").append(toPlain(decode(raw)));
                            count++;
                        }
                        zip.closeEntry();
                    }
                }
                result=out.toString();
            } else result=toPlain(decode(bytes(in,5*1024*1024)));
        }
        if(result.isEmpty())throw new Exception("Не удалось извлечь текст книги");
        JSONObject r=new JSONObject();r.put("text",result.substring(0,Math.min(350000,result.length())));return r;
    }
    private byte[] bytesNoClose(InputStream in,int limit)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n,all=0;
        while((n=in.read(b))!=-1){all+=n;if(all>limit)break;out.write(b,0,n);}return out.toByteArray();
    }
    private String toPlain(String s){
        if(s.trim().startsWith("<")){
            String v=s.replaceAll("(?i)</(?:p|v|title|subtitle|section|h[1-6])>","</p><p>")
             .replaceAll("(?i)<(?:binary|stylesheet)[^>]*>.*?</(?:binary|stylesheet)>","");
            return clean(v).replaceAll("\\n{3,}","\n\n");
        }
        return s;
    }
    private class Bridge{
        @JavascriptInterface public void feed(String path,String id){io.execute(()->{try{reply(id,true,new JSONObject(catalog(path)));}catch(Exception e){reply(id,false,e.getMessage());}});}
        @JavascriptInterface public void search(String q,String type,String id){io.execute(()->{try{
            if(q.trim().length()<2||q.length()>140)throw new Exception("Введите хотя бы два символа");
            String t=Arrays.asList("books","authors","sequences").contains(type)?type:"books";
            String path="/opds/search?searchTerm="+URLEncoder.encode(q,"UTF-8")+"&searchType="+t;
            reply(id,true,new JSONObject(catalog(path)));
        }catch(Exception e){reply(id,false,e.getMessage());}});}
        @JavascriptInterface public void download(String url,String title,String author,String format,String id){io.execute(()->{try{reply(id,true,MainActivity.this.download(url,title,author,format,id));}catch(Exception e){reply(id,false,e.getMessage());}});}
        @JavascriptInterface public void preview(String uri,String format,String id){io.execute(()->{try{reply(id,true,MainActivity.this.preview(uri,format));}catch(Exception e){reply(id,false,e.getMessage());}});}
        @JavascriptInterface public void openFile(String uri,String format){runOnUiThread(()->{try{
            Uri u=Uri.parse(uri);if(!"content".equals(u.getScheme())||!"media".equals(u.getAuthority()))throw new Exception();
            String mime=getContentResolver().getType(u);
            if(mime==null)mime="application/octet-stream";
            Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(u,mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,"Открыть книгу"));
        }catch(Exception e){toast("Для чтения этого формата установите подходящую читалку");}});}
        @JavascriptInterface public void setHost(String host){
            if(Arrays.asList("https://http.flibusta.is","https://flibusta.is","https://flub.flibusta.is","http://http.flibusta.is").contains(host))getPreferences(0).edit().putString("host",host).apply();
        }
        @JavascriptInterface public String getHost(){return base();}
    }
}
