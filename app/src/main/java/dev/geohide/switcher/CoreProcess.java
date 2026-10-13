package dev.geohide.switcher;
import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
final class CoreProcess implements AutoCloseable {
    final Process process;private final File config;
    private CoreProcess(Process process,File config){this.process=process;this.config=config;drain(process);}
    static String executable(Context c){return c.getApplicationInfo().nativeLibraryDir+"/libsingbox.so";}
    private static void drain(Process p){Thread t=new Thread(()->{try(InputStream in=p.getInputStream()){byte[] b=new byte[2048];while(in.read(b)!=-1){}}catch(IOException ignored){}},"Proxy-output-discard");t.setDaemon(true);t.start();}
    static CoreProcess start(Context c,JSONObject json)throws Exception{
        File config=File.createTempFile("proxy-",".json",c.getNoBackupFilesDir());
        try{try(FileOutputStream out=new FileOutputStream(config)){out.write(json.toString().getBytes(StandardCharsets.UTF_8));}
            Process check=new ProcessBuilder(executable(c),"check","-c",config.getAbsolutePath()).redirectErrorStream(true).start();drain(check);
            boolean finished;try{finished=check.waitFor(7,TimeUnit.SECONDS);}catch(InterruptedException e){check.destroyForcibly();Thread.currentThread().interrupt();throw e;}
            if(!finished){check.destroyForcibly();throw new IOException("Ядро не завершило проверку конфигурации");}
            if(check.exitValue()!=0)throw new IOException("Ядро отклонило конфигурацию сервера");
            return new CoreProcess(new ProcessBuilder(executable(c),"run","-c",config.getAbsolutePath()).redirectErrorStream(true).start(),config);
        }catch(Exception e){config.delete();throw e;}
    }
    @Override public void close(){process.destroy();try{if(!process.waitFor(700,TimeUnit.MILLISECONDS)){process.destroyForcibly();process.waitFor(700,TimeUnit.MILLISECONDS);}}catch(InterruptedException e){Thread.currentThread().interrupt();process.destroyForcibly();}finally{config.delete();}}
}
