package com.lgns;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.SecretKey;

/**
 * 密钥库管理器
 * 私钥AES‑GCM加密存储到files目录 keys.enc；磁盘不落地明文私钥，内存解密临时使用
 * 密钥保存在AndroidKeyStore，APP重启密钥不会丢失
 */
public class KeyStoreManager {
    private final File encFile;
    private final SecretKey mAesKey;

    // 双参数构造器
    public KeyStoreManager(Context ctx, SecretKey aesKey) {
        this.mAesKey = aesKey;
        encFile = new File(ctx.getFilesDir(), "keys.enc");
    }

    /**
     * UI新增私钥：加密后追加写入磁盘文件
     * @param plainPrivateKey 明文私钥
     * @throws Exception DUPLICATE_KEY 代表私钥已存在；加密/IO异常
     */
    public synchronized void addPrivateKey(String plainPrivateKey) throws Exception {
        boolean exist = isPlainPrivateKeyExist(plainPrivateKey);
        if (exist) {
            throw new Exception("DUPLICATE_KEY");
        }
        String cipherText = PrivateKeyEncryptUtil.encrypt(plainPrivateKey, mAesKey);
        try (FileWriter fw = new FileWriter(encFile, true)) {
            fw.write(cipherText);
            fw.write("\n");
        }
    }

    /**
     * 判断明文私钥是否已经存在
     * GCM模式每次加密IV随机，密文不可比对；只能解密全部做明文对比
     * @param plainPrivateKey 待检测明文私钥
     * @return true 已存在
     * @throws Exception IO、解密异常
     */
    public synchronized boolean isPlainPrivateKeyExist(String plainPrivateKey) throws Exception {
        List<String> cipherList = loadCipherTextList();
        for (String cipher : cipherList) {
            String plain = PrivateKeyEncryptUtil.decrypt(cipher, mAesKey);
            if (plain.equals(plainPrivateKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【任务业务使用】加载全部私钥：磁盘读取密文，解密返回明文List（仅内存临时持有）
     * @return 解密后的私钥集合
     * @throws Exception IO/解密异常
     */
    public synchronized List<String> loadAllPrivateKeys() throws Exception {
        List<String> result = new ArrayList<>();
        if (!encFile.exists()) {
            return result;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(encFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String plain = PrivateKeyEncryptUtil.decrypt(line, mAesKey);
                result.add(plain);
            }
        }
        return result;
    }

    /**
     * 【UI界面展示用】只读取原始密文行，**不解密**，界面展示密文字符串
     */
    public synchronized List<String> loadCipherTextList() throws Exception {
        List<String> cipherList = new ArrayList<>();
        if (!encFile.exists()) {
            return cipherList;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(encFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    cipherList.add(line);
                }
            }
        }
        return cipherList;
    }

    /**
     * 删除指定索引的密文行
     */
    public synchronized void removeByIndex(int index) throws Exception {
        List<String> allCipher = loadCipherTextList();
        if(index <0 || index >= allCipher.size()){
            return;
        }
        allCipher.remove(index);
        //回写全部
        try(FileWriter fw = new FileWriter(encFile,false)){
            for(String s : allCipher){
                fw.write(s);
                fw.write("\n");
            }
        }
    }

    /**
     * 删除全部密钥文件
     */
    public synchronized void clearAllKeys() {
        if (encFile.exists()) {
            encFile.delete();
        }
    }

    /**
     * 批量删除多条密文（传入要删除的下标集合）
     */
    public synchronized void removeByIndexList(List<Integer> removeIndexList) throws Exception {
        List<String> allCipher = loadCipherTextList();
        // 从大到小删除，防止下标移位错乱！！
        removeIndexList.sort((o1, o2) -> Integer.compare(o2, o1));
        for(Integer idx : removeIndexList){
            if(idx >=0 && idx < allCipher.size()){
                allCipher.remove((int)idx);
            }
        }
        //回写文件
        try(FileWriter fw = new FileWriter(encFile,false)){
            for(String s : allCipher){
                fw.write(s);
                fw.write("\n");
            }
        }
    }

    /**
     * 对外：明文加密得到密文（仅UI，不做查重）
     */
    public String encryptPlainKey(String plainPrivateKey) throws Exception{
        return PrivateKeyEncryptUtil.encrypt(plainPrivateKey, mAesKey);
    }

    /**
     * 业务任务接口：加载全部明文私钥，给ArenaTask调用
     */
    public synchronized List<String> loadAllPrivateKey() throws Exception{
        return loadAllPrivateKeys();
    }
}
