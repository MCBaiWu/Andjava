package com.andjava.ide;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

public class ApkSigner {

public static void sign(String inputApk,
    String outputApk,
    String pk8Path,
     String x509Path) throws Exception {

    X509Certificate cert = loadCertificate(x509Path);
                            PrivateKey privateKey = loadPrivateKey(pk8Path);
                            SignatureFiles files = generateFiles(inputApk, cert, privateKey);
                            writeApk(inputApk, outputApk, files);
        }

        // ==================== 加载密钥 ====================
        private static X509Certificate loadCertificate(String path) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
    FileInputStream fis = new FileInputStream(path);
    try { return (X509Certificate) cf.generateCertificate(fis); } finally { fis.close(); }
    }

        private static PrivateKey loadPrivateKey(String path) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FileInputStream fis = new FileInputStream(path);
            byte[] buf = new byte[8192]; int len;
        try {
            while ((len = fis.read(buf)) != -1) bos.write(buf, 0, len);
        } finally { fis.close(); }
    // RSA 密钥，若为 EC 改 "EC"
    KeyFactory kf = KeyFactory.getInstance("RSA");
    return kf.generatePrivate(new PKCS8EncodedKeySpec(bos.toByteArray()));
        }

        // ==================== 签名文件结构 ====================
            private static class SignatureFiles {
            byte[] mf, sf, rsa;
            }

            private static SignatureFiles generateFiles(String apkPath,
        X509Certificate cert,
            PrivateKey key) throws Exception {
        JarFile jar = new JarFile(apkPath, false);
        try {
        SignatureFiles f = new SignatureFiles();

        // --------- MANIFEST.MF ---------
        Manifest mf = new Manifest();
    mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    mf.getMainAttributes().putValue("Created-By", "1.0 (Android SignApk)");
    Map<String, byte[]> digests = new HashMap<String, byte[]>();

        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
        JarEntry e = entries.nextElement();
    if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
    InputStream is = jar.getInputStream(e);
    byte[] d = sha1(is); is.close();
                                                         Attributes a = new Attributes();
                                                         a.putValue("SHA1-Digest", b64(d));
        mf.getEntries().put(e.getName(), a);
        digests.put(e.getName(), d);
            }
            ByteArrayOutputStream mfBuf = new ByteArrayOutputStream();
            mf.write(mfBuf);
            f.mf = mfBuf.toByteArray();

            // --------- CERT.SF ---------
            Manifest sf = new Manifest();
            sf.getMainAttributes().put(Attributes.Name.SIGNATURE_VERSION, "1.0");
            sf.getMainAttributes().putValue("Created-By", "1.0 (Android SignApk)");
            sf.getMainAttributes().putValue("SHA1-Digest-Manifest", b64(sha1(f.mf)));
            for (String name : digests.keySet()) {
            String entryStr = "Name: " + name + "\r\n" +
                "SHA1-Digest: " + b64(digests.get(name)) + "\r\n\r\n";
                byte[] d = sha1(entryStr.getBytes("UTF-8"));
                Attributes a = new Attributes();
                a.putValue("SHA1-Digest", b64(d));
                sf.getEntries().put(name, a);
                }
                ByteArrayOutputStream sfBuf = new ByteArrayOutputStream();
                sf.write(sfBuf);
                f.sf = sfBuf.toByteArray();

                // --------- CERT.RSA ---------
                f.rsa = buildPkcs7(f.sf, cert, key);
            return f;
            } finally { jar.close(); }
            }

            /* 构建符合 Android 验证的 PKCS#7 SignedData */
            private static byte[] buildPkcs7(byte[] sfBytes,
            X509Certificate cert,
            PrivateKey key) throws Exception {
            // 1. 计算 SF 文件的 SHA1
            byte[] messageDigest = sha1(sfBytes);

            // 2. 构造 signed attributes（两个属性）
            byte[] attrContentType = makeSequence(
            makeOID("1.2.840.113549.1.9.3"),       // contentType
                makeSet(makeOID("1.2.840.113549.1.7.1")) // data
                    );
                byte[] attrMessageDigest = makeSequence(
                makeOID("1.2.840.113549.1.9.4"),       // messageDigest
                makeSet(makeOctetString(messageDigest))
                );

            // 用于签名的数据：attributes 的 SET DER 编码
            byte[] signedAttributesDer = makeSet(attrContentType, attrMessageDigest);

            // 3. 签名：用私钥对 signedAttributesDer 签名
            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(key);
            signer.update(signedAttributesDer);
            byte[] signatureValue = signer.sign();

            // 4. 构造 SignerInfo
        byte[] signerInfo = makeSequence(
            makeInt(1),                           // version (1 表示有 signedAttributes)
        makeIssuerAndSerial(cert),            // IssuerAndSerialNumber
    makeAlgorithmIdentifier("1.3.14.3.2.26", null), // digestAlgorithm SHA1
    makeImplicit(0,                       // authenticatedAttributes [0] IMPLICIT
    concat(attrContentType, attrMessageDigest)), // 注意直接拼接，无 SET 标签
     makeAlgorithmIdentifier("1.2.840.113549.1.1.5", null), // signatureAlgorithm SHA1withRSA
     makeOctetString(signatureValue),      // signature
    makeImplicit(1, new byte[0])          // unauthenticatedAttributes [1] IMPLICIT (empty)
                                     );

        // 5. 证书集（[0] IMPLICIT SET OF Certificate）
        byte[] certSet = makeImplicit(0, cert.getEncoded()); // 直接证书 DER，无 SET 标签

        // 6. ContentInfo（contentType = data）
        byte[] contentInfo = makeSequence(makeOID("1.2.840.113549.1.7.1"));

            // 7. SignedData
        byte[] signedData = makeSequence(
        makeInt(1),                           // version
        makeSet(makeAlgorithmIdentifier("1.3.14.3.2.26", null)), // digestAlgorithms
            contentInfo,                          // contentInfo
            certSet,                              // certificates [0] IMPLICIT
        new byte[0],                          // crls [1] (empty)
        makeSet(signerInfo)                  // signerInfos
        );

        // 8. 最外层 ContentInfo (id-signedData)
        byte[] oid = makeOID("1.2.840.113549.1.7.2");
        return makeSequence(oid, makeImplicit(0, signedData));
        }

        // ==================== ASN.1 DER 编码 ====================
        private static byte[] makeInt(int v) {
        byte[] data = BigInteger.valueOf(v).toByteArray();
        return makeTLV(0x02, data);
        }

        private static byte[] makeOID(String oid) {
        String[] ps = oid.split("\\.");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(40 * Integer.parseInt(ps[0]) + Integer.parseInt(ps[1]));
            for (int i = 2; i < ps.length; i++) {
            long val = Long.parseLong(ps[i]);
            byte[] b = new byte[10]; int idx = b.length;
            b[--idx] = (byte) (val & 0x7F); val >>= 7;
            while (val > 0) { b[--idx] = (byte) ((val & 0x7F) | 0x80); val >>= 7; }
            out.write(b, idx, b.length - idx);
        }
        return makeTLV(0x06, out.toByteArray());
        }

        private static byte[] makeOctetString(byte[] data) {
        return makeTLV(0x04, data);
        }

        private static byte[] makeSequence(byte[]... items) {
        return makeTLV(0x30, concat(items));
            }

            private static byte[] makeSet(byte[]... items) {
            return makeTLV(0x31, concat(items));
            }

        /** [tag] IMPLICIT，直接添加内容，不再增加内部标签 */
        private static byte[] makeImplicit(int tagNumber, byte[] content) {
        return makeTLV(0xA0 | tagNumber, content);
        }

        private static byte[] makeAlgorithmIdentifier(String oid, byte[] params) {
    if (params == null || params.length == 0) params = new byte[]{0x05, 0x00}; // NULL
    return makeSequence(makeOID(oid), params);
    }

        private static byte[] makeIssuerAndSerial(X509Certificate cert) {
            try {
            // Android API 23+ 可用
            javax.security.auth.x500.X500Principal issuer = cert.getIssuerX500Principal();
            return makeSequence(issuer.getEncoded(), makeTLV(0x02, cert.getSerialNumber().toByteArray()));
        } catch (Exception e) {
        throw new RuntimeException("无法提取 Issuer", e);
        }
        }

        private static byte[] makeTLV(int tag, byte[] content) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(tag);
    int len = content.length;
        if (len < 128) out.write(len);
        else if (len < 256) { out.write(0x81); out.write(len); }
        else { out.write(0x82); out.write(len >> 8); out.write(len & 0xFF); }
            try { out.write(content); } catch (Exception ignored) {}
            return out.toByteArray();
            }

        private static byte[] concat(byte[]... arrays) {
    int total = 0; for (byte[] a : arrays) total += a.length;
    byte[] r = new byte[total]; int pos = 0;
    for (byte[] a : arrays) { System.arraycopy(a, 0, r, pos, a.length); pos += a.length; }
        return r;
        }

        // ==================== APK 写入 ====================
        private static void writeApk(String inApk, String outApk, SignatureFiles f) throws Exception {
            JarFile jar = new JarFile(inApk, false);
            FileOutputStream fos = new FileOutputStream(outApk);
            JarOutputStream jos = new JarOutputStream(fos);
            try {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.getName().startsWith("META-INF/")) continue;
            jos.putNextEntry(new JarEntry(e.getName()));
            InputStream is = jar.getInputStream(e);
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) != -1) jos.write(buf, 0, n);
    is.close(); jos.closeEntry();
    }
    jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF")); jos.write(f.mf); jos.closeEntry();
        jos.putNextEntry(new JarEntry("META-INF/CERT.SF"));   jos.write(f.sf); jos.closeEntry();
    jos.putNextEntry(new JarEntry("META-INF/CERT.RSA"));  jos.write(f.rsa); jos.closeEntry();
    } finally { jar.close(); jos.close(); fos.close(); }
    }

    // ==================== 工具 ====================
    private static byte[] sha1(InputStream in) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA1");
        byte[] buf = new byte[8192]; int n;
    while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
    return md.digest();
    }
        private static byte[] sha1(byte[] d) throws Exception {
        return MessageDigest.getInstance("SHA1").digest(d);
        }
    private static String b64(byte[] d) {
    return Base64.encodeToString(d, Base64.NO_WRAP);
    }
        }
