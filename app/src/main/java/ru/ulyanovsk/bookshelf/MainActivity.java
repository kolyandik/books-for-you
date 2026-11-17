package ru.ulyanovsk.bookshelf;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.content.Intent;
import android.database.Cursor;
import android.provider.OpenableColumns;
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
import javax.xml.parsers.DocumentBuilder;
import org.xml.sax.SAXException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import javax.net.ssl.SSLException;
import java.io.IOException;
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
    private static final int FILE_PICKER=716;
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
                return !"file:///android_asset/index.html".equals(request.getUrl().toString());
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

    private String base(){ return getPreferences(0).getString("host","https://flibusta.is"); }
    private static String friendly(Exception e){
        if(e instanceof UnknownHostException) return "Сервер не найден (DNS). Проверьте адрес источника и интернет.";
        if(e instanceof SocketTimeoutException) return "Источник слишком долго не отвечает. Каталог или выдача книги на сервере недоступны.";
        if(e instanceof SSLException) return "Ошибка защищённого соединения HTTPS: "+e.getClass().getSimpleName();
        if(e instanceof ConnectException) return "Соединение отклонено. Источник не принимает подключения.";
        String m=e.getMessage();
        return m==null||m.trim().isEmpty()?"Ошибка соединения: "+e.getClass().getSimpleName():m;
    }
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
        int redirects=0, retries=0;
        while(redirects<5){
            URL validated=checked(url.toString(),catalog);
            HttpURLConnection c=(HttpURLConnection)validated.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000); c.setReadTimeout(25000);
            c.setRequestProperty("User-Agent","BookShelf-Ulyanovsk/1.1 (Android; OPDS Reader)");
            c.setRequestProperty("Accept-Encoding","identity");
            c.setRequestProperty("Accept",catalog?"application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.3":"application/octet-stream, */*;q=0.7");
            final int status;
            try{status=c.getResponseCode();}
            catch(SocketTimeoutException e){
                c.disconnect();
                if(retries++<1){Thread.sleep(700);continue;}
                throw e;
            }
            if(status>=300 && status<400){
                String loc=c.getHeaderField("Location"); c.disconnect();
                if(loc==null)throw new Exception("Источник перенаправил запрос без адреса");
                url=new URL(validated,loc); redirects++; continue;
            }
            if(status==429||status==502||status==503||status==504){
                c.disconnect();
                if(retries++<1){Thread.sleep(800);continue;}
                throw new Exception("Сервер временно не отдаёт "+(catalog?"каталог":"книгу")+" (HTTP "+status+"). Повторите позже.");
            }
            if(status==401||status==403){c.disconnect();throw new Exception("Источник ограничил доступ (HTTP "+status+"). Возможно, требуется вход или книга недоступна.");}
            if(status==404||status==410){c.disconnect();throw new Exception("Книга или раздел отсутствует на сервере (HTTP "+status+").");}
            if(status<200||status>=300){c.disconnect();throw new Exception("Источник ответил HTTP "+status+".");}
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
    private static boolean isAtom(Element e,String name){
        return NS.equals(e.getNamespaceURI())&&name.equals(e.getLocalName());
    }
    private static String atomChild(Element e,String name){
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()){
            if(n instanceof Element && isAtom((Element)n,name))return n.getTextContent().trim();
        }
        return "";
    }
    private JSONObject parseCatalog(byte[] raw,String source) throws Exception{
        String scan=new String(raw,StandardCharsets.ISO_8859_1).replace(String.valueOf((char)0), "");
        if(Pattern.compile("(?i)<!\\s*(?:DOCTYPE|ENTITY)\\b").matcher(scan).find())
            throw new Exception("В XML обнаружены запрещённые декларации DTD/ENTITY");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);factory.setExpandEntityReferences(false);
        DocumentBuilder builder=factory.newDocumentBuilder();
        builder.setEntityResolver((publicId,systemId)->{throw new SAXException("Внешние XML-сущности запрещены");});
        Document doc=builder.parse(new java.io.ByteArrayInputStream(raw));
        Element root=doc.getDocumentElement();
        if(!NS.equals(root.getNamespaceURI())||!("feed".equals(root.getLocalName())||"entry".equals(root.getLocalName())))
            throw new Exception("Сервер вернул не OPDS XML. Возможно, это страница ошибки или блокировки.");
        JSONObject result=new JSONObject();
        result.put("title",atomChild(root,"title"));result.put("next",JSONObject.NULL);
        JSONArray items=new JSONArray();result.put("items",items);
        URL sourceUrl=new URL(source);
        if(isAtom(root,"feed")){
            for(Node n=root.getFirstChild();n!=null;n=n.getNextSibling()){
                if(!(n instanceof Element))continue;
                Element e=(Element)n;
                if(isAtom(e,"link")&&"next".equals(e.getAttribute("rel"))){
                    try{result.put("next",checked(new URL(sourceUrl,e.getAttribute("href")).toString(),true).toString());}
                    catch(Exception ignored){}
                }
            }
        }
        NodeList entries=isAtom(root,"entry")?null:root.getElementsByTagNameNS(NS,"entry");
        int count=entries==null?1:Math.min(entries.getLength(),100);
        for(int i=0;i<count;i++){
            Element entry=entries==null?root:(Element)entries.item(i);
            JSONObject book=new JSONObject();
            book.put("title",atomChild(entry,"title"));
            String author="";
            for(Node n=entry.getFirstChild();n!=null;n=n.getNextSibling()){
                if(n instanceof Element && isAtom((Element)n,"author")){
                    String name=atomChild((Element)n,"name");
                    if(!name.isEmpty())author+=(author.isEmpty()?"":", ")+name;
                }
            }
            book.put("author",author);
            String summary=atomChild(entry,"summary");
            if(summary.isEmpty())summary=atomChild(entry,"content");
            String description=clean(summary);book.put("summary",description.substring(0,Math.min(4000,description.length())));
            JSONArray formats=new JSONArray();String nav="",site="",bookId="",authorId="";
            Matcher tagId=Pattern.compile("(?:tag:book:|/b/)(\\d{3,})").matcher(atomChild(entry,"id"));
            if(tagId.find())bookId=tagId.group(1);
            for(Node n=entry.getFirstChild();n!=null;n=n.getNextSibling()){
                if(!(n instanceof Element)||!isAtom((Element)n,"link"))continue;
                Element l=(Element)n;
                String href=l.getAttribute("href");if(href.isEmpty())continue;
                String rel=l.getAttribute("rel"),type=l.getAttribute("type"),linkTitle=l.getAttribute("title");
                String absolute;
                try{absolute=new URL(sourceUrl,href).toString();}
                catch(Exception e){continue;}
                Matcher matcher=Pattern.compile("/b/(\\d+)(?:/|$)").matcher(absolute);
                if(matcher.find())bookId=matcher.group(1);
                if(("alternate".equals(rel)||linkTitle.contains("Книга на сайте")) && matcher.find(0)){
                    try{URL url=new URL(absolute);if(HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT)))site=absolute;}
                    catch(Exception ignored){}
                }
                // OPDS navigation entries do not always use rel="subsection".
                // For author and series search results, a valid Atom catalog link
                // is stronger evidence of a folder than the presence of a summary.
                boolean atomFeed=type.toLowerCase(Locale.ROOT).contains("application/atom+xml");
                boolean navigationRel="subsection".equals(rel)||"collection".equals(rel)
                    ||rel.contains("navigation")||rel.contains("subsection");
                boolean catalogPath=false;
                try{
                    URL candidate=new URL(absolute);
                    catalogPath=candidate.getPath().startsWith("/opds/")
                        ||candidate.getPath().equals("/opds");
                }catch(Exception ignored){}
                if(catalogPath && (atomFeed||navigationRel||rel.isEmpty()||"alternate".equals(rel))){
                    try{
                        String candidate=checked(absolute,true).toString();
                        // Never replace a useful navigation target with the feed's self link.
                        if(!"self".equals(rel) && !"start".equals(rel))nav=candidate;
                    }catch(Exception ignored){}
                }
                // Some author search feeds contain only an author profile URI.
                // Do not treat an author profile as a downloadable book.
                if(authorId.isEmpty() && (linkTitle.contains("книг")||linkTitle.contains("автора")
                    ||"related".equals(rel)||"alternate".equals(rel))){
                    Matcher a=Pattern.compile("(?i)/a/(\\d+)(?:/|$)").matcher(absolute);
                    if(a.find())authorId=a.group(1);
                }
                String f=fmt(type,absolute);
                if(f.isEmpty()&&rel.contains("acquisition"))f=fmt(linkTitle,absolute);
                if((rel.contains("acquisition")||(!f.isEmpty()&&!"alternate".equals(rel)))&&!f.isEmpty()){
                    try{
                        checked(absolute,false);
                        JSONObject opt=new JSONObject();opt.put("format",f);opt.put("url",absolute);
                        formats.put(opt);
                    }catch(Exception ignored){}
                }
            }
            // Fallback only for a real author listing (not for a book's related author).
            String entryId=atomChild(entry,"id");
            boolean authorListing=sourceUrl.contains("searchType=authors")
                ||sourceUrl.contains("/opds/author")
                ||Pattern.compile("(?i)\\b\\d+\\s+книг(?:а|и)?\\b").matcher(description).find();
            if(nav.isEmpty() && bookId.isEmpty() && formats.length()==0 && authorListing){
                if(authorId.isEmpty()){
                    Matcher a=Pattern.compile("(?i)(?:tag:author:|/a/)(\\d+)").matcher(entryId);
                    if(a.find())authorId=a.group(1);
                }
                if(!authorId.isEmpty()){
                    try{nav=checked(new URL(sourceUrl,"/opds/author/"+authorId).toString(),true).toString();}
                    catch(Exception ignored){}
                }
            }
            // Real book listings sometimes omit download links and offer details only.
            if(nav.isEmpty() && !bookId.isEmpty() && formats.length()==0){
                try{nav=checked(new URL(sourceUrl,"/opds/book/"+bookId).toString(),true).toString();}
                catch(Exception ignored){}
            }
            book.put("formats",formats);book.put("navigation",nav);
            book.put("id",bookId);book.put("site",site);
            boolean section=formats.length()==0 && bookId.isEmpty() && !nav.isEmpty();
            // An entry with neither navigation nor a book identifier/download is not a book.
            // It must not expose a useless download dialog.
            String kind=section?"section":(formats.length()>0||!bookId.isEmpty()?"book":"unavailable");
            book.put("kind",kind);
            items.put(book);
        }
        return result;
    }
    private String catalog(String path) throws Exception {
        URL url=checked(path,true);
        HttpURLConnection conn=connection(url,true);
        try {if(conn.getContentLengthLong()>MAX_CATALOG)throw new Exception("Каталог слишком большой");
            byte[] raw=bytes(conn.getInputStream(),MAX_CATALOG);
            String contentType=conn.getContentType();
            if(contentType!=null&&contentType.toLowerCase(Locale.ROOT).contains("text/html"))
                throw new Exception("Сервер отдал HTML вместо каталога OPDS — это может быть страница ошибки или ограничения доступа.");
            if(raw.length<30)throw new Exception("Пустой ответ каталога");
            return parseCatalog(raw,conn.getURL().toString()).toString();
        } finally {conn.disconnect();}
    }
    private JSONObject diagnose() throws Exception{
        JSONObject r=new JSONObject();r.put("host",base());
        URL u=checked("/opds",true);
        try{
            HttpURLConnection c=connection(u,true);
            try{
                String mime=c.getContentType();
                byte[] b=bytes(c.getInputStream(),MAX_CATALOG);
                r.put("bytes",b.length);r.put("type",mime==null?"":mime);
                if(b.length<30)throw new Exception("Сервер вернул пустой каталог");
                JSONObject parsed=parseCatalog(b,c.getURL().toString());
                r.put("entries",parsed.getJSONArray("items").length());
                r.put("status","OPDS-каталог отвечает. Доступность отдельных книг проверяется при загрузке.");
                return r;
            }finally{c.disconnect();}
        }catch(Exception e){throw new Exception("Проверка "+base()+"/opds: "+friendly(e));}
    }
    private JSONObject selfTest() throws Exception{
        String xml="<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            +"<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Тестовый каталог</title>"
            +"<entry><id>tag:book:123456</id><title>Тестовая книга</title>"
            +"<author><name>Тестовый автор</name></author>"
            +"<link rel=\"http://opds-spec.org/acquisition\" type=\"application/epub+zip\" href=\"/b/123456/epub\"/>"
            +"</entry><entry><title>Раздел</title>"
            +"<link rel=\"subsection\" type=\"application/atom+xml\" href=\"/opds/genres\"/>"
            +"</entry><entry><id>tag:author:87203</id><title>Шаттам Максим</title>"
            +"<content>20 книг</content>"
            +"<link rel=\"related\" type=\"application/atom+xml;profile=opds-catalog\" href=\"/opds/author/87203\"/>"
            +"</entry><entry><id>tag:author:87204</id><title>Другой автор</title>"
            +"<content>15 книг</content>"
            +"<link rel=\"alternate\" type=\"text/html\" href=\"/a/87204\"/>"
            +"</entry></feed>";
        JSONObject parsed=parseCatalog(xml.getBytes(StandardCharsets.UTF_8),"https://flibusta.is/opds");
        JSONArray items=parsed.getJSONArray("items");
        if(items.length()!=4||items.getJSONObject(0).getJSONArray("formats").length()!=1||
           !"section".equals(items.getJSONObject(1).getString("kind"))||
           !"section".equals(items.getJSONObject(2).getString("kind"))||
           !items.getJSONObject(2).getString("navigation").contains("/opds/author/87203")||
           !"section".equals(items.getJSONObject(3).getString("kind"))||
           !items.getJSONObject(3).getString("navigation").contains("/opds/author/87204"))
            throw new Exception("Самопроверка парсера не пройдена: книга/автор/раздел");
        JSONObject result=new JSONObject();result.put("status","Проверены книга, раздел, автор с 20 книгами и запасная ссылка автора. Интернет не проверялся.");
        result.put("books",1);result.put("sections",3);return result;
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
            if(total<20||magic.startsWith("<!doctype")||magic.startsWith("<html")||magic.startsWith("<head")||magic.startsWith("<body"))throw new Exception("Источник вернул HTML-страницу вместо книги. Возможно, файл удалён или требуется вход.");
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
    private static String extForName(String filename){
        String f=filename.toLowerCase(Locale.ROOT);
        for(String ext:new String[]{".fb2.zip",".txt.zip",".html.zip",".rtf.zip",".epub",".fb2",".pdf",".txt",".mobi",".rtf",".html"})
            if(f.endsWith(ext))return ext;
        return "";
    }
    private JSONObject copyPickedFile(Uri source) throws Exception{
        String srcName="Книга.fb2";
        try(Cursor c=getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst()){String value=c.getString(0);if(value!=null)srcName=value;}
        }catch(Exception ignored){}
        String ext=extForName(srcName);
        if(ext.isEmpty())throw new Exception("Поддерживаются FB2, EPUB, PDF, TXT, RTF и их ZIP-архивы");
        String filename=srcName.replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]","_");
        Uri dst=null;long total=0;
        try{
            ContentValues v=new ContentValues();v.put(MediaStore.Downloads.DISPLAY_NAME,filename);
            v.put(MediaStore.Downloads.MIME_TYPE,"application/octet-stream");
            v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Книжная полка");
            v.put(MediaStore.Downloads.IS_PENDING,1);
            dst=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
            if(dst==null)throw new Exception("Нет доступа к сохранению файла");
            try(InputStream in=getContentResolver().openInputStream(source);OutputStream out=getContentResolver().openOutputStream(dst)){
                if(in==null||out==null)throw new Exception("Не удалось открыть файл");
                byte[] buffer=new byte[32768];int n;
                while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_BOOK)throw new Exception("Слишком большой файл");out.write(buffer,0,n);}
            }
            if(total<5)throw new Exception("Файл пустой");
            ContentValues done=new ContentValues();done.put(MediaStore.Downloads.IS_PENDING,0);
            getContentResolver().update(dst,done,null,null);
            String fmt=ext.contains("fb2")?"FB2":ext.contains("epub")?"EPUB":ext.contains("pdf")?"PDF":ext.contains("txt")?"TXT":ext.contains("rtf")?"RTF":"HTML";
            JSONObject info=new JSONObject();info.put("name",filename);info.put("uri",dst.toString());info.put("format",fmt);
            info.put("size",total);info.put("title",filename.substring(0,filename.length()-ext.length()));return info;
        }catch(Exception e){if(dst!=null)getContentResolver().delete(dst,null,null);throw e;}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=FILE_PICKER)return;
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri picked=data.getData();
        io.execute(()->{
            try{JSONObject entry=copyPickedFile(picked);String js="window.__bookshelfImported("+entry.toString()+")";
                runOnUiThread(()->{if(web!=null)web.evaluateJavascript(js,null);});
            }catch(Exception e){toast("Не удалось добавить: "+friendly(e));}
        });
    }
    private class Bridge{
        @JavascriptInterface public void selfTest(String id){io.execute(()->{try{reply(id,true,MainActivity.this.selfTest());}catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void diagnose(String id){io.execute(()->{try{reply(id,true,MainActivity.this.diagnose());}catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void feed(String path,String id){io.execute(()->{try{reply(id,true,new JSONObject(catalog(path)));}catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void search(String q,String type,String id){io.execute(()->{try{
            if(q.trim().length()<2||q.length()>140)throw new Exception("Введите хотя бы два символа");
            String t=Arrays.asList("books","authors","sequences").contains(type)?type:"books";
            String path="/opds/search?searchTerm="+URLEncoder.encode(q,"UTF-8")+"&searchType="+t;
            reply(id,true,new JSONObject(catalog(path)));
        }catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void download(String url,String title,String author,String format,String id){io.execute(()->{try{reply(id,true,MainActivity.this.download(url,title,author,format,id));}catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void preview(String uri,String format,String id){io.execute(()->{try{reply(id,true,MainActivity.this.preview(uri,format));}catch(Exception e){reply(id,false,friendly(e));}});}
        @JavascriptInterface public void pickFile(){runOnUiThread(()->{
            try{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);picker.addCategory(Intent.CATEGORY_OPENABLE);
                picker.setType("*/*");startActivityForResult(picker,FILE_PICKER);
            }catch(Exception e){toast("Не удалось открыть выбор файлов");}
        });}
        @JavascriptInterface public void openFile(String uri,String format){runOnUiThread(()->{try{
            Uri u=Uri.parse(uri);if(!"content".equals(u.getScheme())||!"media".equals(u.getAuthority()))throw new Exception();
            String mime=getContentResolver().getType(u);
            if(mime==null||"application/octet-stream".equals(mime)){
                switch(format.toUpperCase(Locale.ROOT)){
                    case "EPUB":mime="application/epub+zip";break;
                    case "PDF":mime="application/pdf";break;
                    case "FB2":mime="application/zip";break;
                    case "TXT":mime="text/plain";break;
                    case "HTML":mime="text/html";break;
                    default:mime="application/octet-stream";
                }
            }
            Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(u,mime);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,"Открыть книгу"));
        }catch(Exception e){toast("Для чтения этого формата установите подходящую читалку");}});}
        @JavascriptInterface public void setHost(String host){
            if(Arrays.asList("https://http.flibusta.is","https://flibusta.is","https://flub.flibusta.is","http://http.flibusta.is").contains(host))getPreferences(0).edit().putString("host",host).apply();
        }
        @JavascriptInterface public String getHost(){return base();}
    }
}
