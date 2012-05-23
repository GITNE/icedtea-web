/*
 * Copyright 2011 Red Hat, Inc.
 *
 * This file is made available under the terms of the Common Public License
 * v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 */

import java.io.BufferedWriter;
import java.io.File;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.sourceforge.jnlp.annotations.Bug;


import org.junit.internal.JUnitSystem;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
/**
 * This class listens for events in junit testsuite and wrote output to xml.
 * Xml tryes to follow ant-tests schema, and is enriched for by-class statistics
 * stdout and err elements are added, but must be filled from elsewhere (eg tee
 * in make) as junit suite and listener run from our executer have no access to
 * them.
 * 
 */
public class JunitLikeXmlOutputListener extends RunListener {

    private BufferedWriter writer;
    private Failure testFailed = null;
    private static final String ROOT = "testsuite";
    private static final String DATE_ELEMENT = "date";
    private static final String TEST_ELEMENT = "testcase";
    private static final String BUGS = "bugs";
    private static final String BUG = "bug";
    private static final String TEST_NAME_ATTRIBUTE = "name";
    private static final String TEST_TIME_ATTRIBUTE = "time";
    private static final String TEST_ERROR_ELEMENT = "error";
    private static final String TEST_CLASS_ATTRIBUTE = "classname";
    private static final String ERROR_MESSAGE_ATTRIBUTE = "message";
    private static final String ERROR_TYPE_ATTRIBUTE = "type";
    private static final String SOUT_ELEMENT = "system-out";
    private static final String SERR_ELEMENT = "system-err";
    private static final String CDATA_START = "<![CDATA[";
    private static final String CDATA_END = "]]>";
    private static final String TEST_CLASS_ELEMENT = "class";
    private static final String STATS_ELEMENT = "stats";
    private static final String CLASSES_ELEMENT = "classes";
    private static final String SUMMARY_ELEMENT = "summary";
    private static final String SUMMARY_TOTAL_ELEMENT = "total";
    private static final String SUMMARY_PASSED_ELEMENT = "passed";
    private static final String SUMMARY_FAILED_ELEMENT = "failed";
    private static final String SUMMARY_IGNORED_ELEMENT = "ignored";
    private long testStart;

    private class ClassCounter {

        Class c;
        int total;
        int failed;
        int passed;
        long time = 0;
    }
    Map<String, ClassCounter> classStats = new HashMap<String, ClassCounter>();

    public JunitLikeXmlOutputListener(JUnitSystem system, File f) {
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void testRunStarted(Description description) throws Exception {
        openElement(ROOT);
        writeElement(DATE_ELEMENT, new Date().toString());
    }

    private void openElement(String name) throws IOException {
        openElement(name, null);
    }

    private void openElement(String name, Map<String, String> atts) throws IOException {
        StringBuilder attString = new StringBuilder();
        if (atts != null) {
            attString.append(" ");
            Set<Entry<String, String>> entries = atts.entrySet();
            for (Entry<String, String> entry : entries) {
                String k=entry.getKey();
                String v= entry.getValue();
                if (v==null)v="null";
                attString.append(k).append("=\"").append(attributize(v)).append("\"");
                attString.append(" ");
            }
        }
        writer.write("<" + name + attString.toString() + ">");
        writer.newLine();
    }

    private static String attributize(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }

    private void closeElement(String name) throws IOException {
        writer.newLine();
        writer.write("</" + name + ">");
        writer.newLine();
    }

    private void writeContent(String content) throws IOException {
        writer.write(CDATA_START + content + CDATA_END);
    }

    private void writeElement(String name, String content) throws IOException {
        writeElement(name, content, null);
    }

    private void writeElement(String name, String content, Map<String, String> atts) throws IOException {
        openElement(name, atts);
        writeContent(content);
        closeElement(name);
    }

    @Override
    public void testStarted(Description description) throws Exception {
        testFailed = null;
        testStart = System.nanoTime() / 1000l / 1000l;
    }

    @Override
    public void testFailure(Failure failure) throws IOException {
        testFailed = failure;
    }

    @Override
    public void testFinished(org.junit.runner.Description description) throws Exception {
        long testTime = System.nanoTime()/1000l/1000l - testStart;
        double testTimeSeconds = ((double) testTime) / 1000d;

        Map<String, String> testcaseAtts = new HashMap<String, String>(3);
        NumberFormat formatter = new DecimalFormat("#0.0000");
        String stringedTime = formatter.format(testTimeSeconds);
        stringedTime.replace(",", ".");
        testcaseAtts.put(TEST_TIME_ATTRIBUTE, stringedTime);
        testcaseAtts.put(TEST_CLASS_ATTRIBUTE, description.getClassName());
        testcaseAtts.put(TEST_NAME_ATTRIBUTE, description.getMethodName());

        openElement(TEST_ELEMENT, testcaseAtts);
        if (testFailed != null) {
            Map<String, String> errorAtts = new HashMap<String, String>(3);

            errorAtts.put(ERROR_MESSAGE_ATTRIBUTE, testFailed.getMessage());
            int i = testFailed.getTrace().indexOf(":");
            if (i >= 0) {
                errorAtts.put(ERROR_TYPE_ATTRIBUTE, testFailed.getTrace().substring(0, i));
            } else {
                errorAtts.put(ERROR_TYPE_ATTRIBUTE, "?");
            }

            writeElement(TEST_ERROR_ELEMENT, testFailed.getTrace(), errorAtts);
        }
        try {
            Class q = description.getTestClass();
            String qs=description.getMethodName();
            if (qs.contains(" - ")) qs=qs.replaceAll(" - .*", "");
            Method qm = q.getMethod(qs);
            Bug b = qm.getAnnotation(Bug.class);
            if (b != null) {
                openElement(BUGS);
                String[] s = b.id();
                for (String string : s) {
                        String ss[]=createBug(string);
                        Map<String, String> visibleNameAtt=new HashMap<String, String>(1);
                        visibleNameAtt.put("visibleName", ss[0]);
                        openElement(BUG,visibleNameAtt);
                        writer.write(ss[1]);
                        closeElement(BUG);
                }
                closeElement(BUGS);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        closeElement(TEST_ELEMENT);
        writer.flush();

        ClassCounter cc = classStats.get(description.getClassName());
        if (cc == null) {
            cc = new ClassCounter();
            cc.c=description.getTestClass();
            classStats.put(description.getClassName(), cc);
        }
        cc.total++;
        cc.time += testTime;
        if (testFailed == null) {
            cc.passed++;
        } else {

            cc.failed++;
        }
    }

    @Override
    public void testRunFinished(Result result) throws Exception {

        writeElement(SOUT_ELEMENT, "@sout@");
        writeElement(SERR_ELEMENT, "@serr@");
        openElement(STATS_ELEMENT);
        openElement(SUMMARY_ELEMENT);
        int passed = result.getRunCount() - result.getFailureCount() - result.getIgnoreCount();
        int failed = result.getFailureCount();
        int ignored = result.getIgnoreCount();
        writeElement(SUMMARY_TOTAL_ELEMENT, String.valueOf(result.getRunCount()));
        writeElement(SUMMARY_FAILED_ELEMENT, String.valueOf(failed));
        writeElement(SUMMARY_IGNORED_ELEMENT, String.valueOf(ignored));
        writeElement(SUMMARY_PASSED_ELEMENT, String.valueOf(passed));
        closeElement(SUMMARY_ELEMENT);
        openElement(CLASSES_ELEMENT);
        Set<Entry<String, ClassCounter>> e = classStats.entrySet();
        for (Entry<String, ClassCounter> entry : e) {

            Map<String, String> testcaseAtts = new HashMap<String, String>(3);
            testcaseAtts.put(TEST_NAME_ATTRIBUTE, entry.getKey());
            testcaseAtts.put(TEST_TIME_ATTRIBUTE, String.valueOf(entry.getValue().time));

            openElement(TEST_CLASS_ELEMENT, testcaseAtts);
            writeElement(SUMMARY_PASSED_ELEMENT, String.valueOf(entry.getValue().passed));
            writeElement(SUMMARY_FAILED_ELEMENT, String.valueOf(entry.getValue().failed));
            writeElement(SUMMARY_IGNORED_ELEMENT, String.valueOf(entry.getValue().total - entry.getValue().failed - entry.getValue().passed));
            writeElement(SUMMARY_TOTAL_ELEMENT, String.valueOf(entry.getValue().total));
            try {
                Bug b = null;
                if (entry.getValue().c != null) {
                    b = (Bug) entry.getValue().c.getAnnotation(Bug.class);
                }
                if (b != null) {
                    openElement(BUGS);
                    String[] s = b.id();
                    for (String string : s) {
                        String ss[]=createBug(string);
                        Map<String, String> visibleNameAtt=new HashMap<String, String>(1);
                        visibleNameAtt.put("visibleName", ss[0]);
                        openElement(BUG,visibleNameAtt);
                        writer.write(ss[1]);
                        closeElement(BUG);
                    }
                    closeElement(BUGS);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            closeElement(TEST_CLASS_ELEMENT);
        }
        closeElement(CLASSES_ELEMENT);
        closeElement(STATS_ELEMENT);

        closeElement(ROOT);
        writer.flush();
        writer.close();

    }


    /**
     * When declare for suite class or for Test-marked method,
     * should be interpreted by report generating tool to links.
     * Known shortcuts are
     * SX  - http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=X
     * PRX - http://icedtea.classpath.org/bugzilla/show_bug.cgi?id=X
     * RHX - https://bugzilla.redhat.com/show_bug.cgi?id=X
     * DX  - http://bugs.debian.org/cgi-bin/bugreport.cgi?bug=X
     * GX  - http://bugs.gentoo.org/show_bug.cgi?id=X
     * CAX - http://server.complang.tuwien.ac.at/cgi-bin/bugzilla/show_bug.cgi?id=X
     * LPX - https://bugs.launchpad.net/bugs/X
     *
     * http://mail.openjdk.java.net/pipermail/distro-pkg-dev/
     * and http://mail.openjdk.java.net/pipermail/ are proceed differently
     *
     * You just put eg @Bug(id="RH12345",id="http:/my.bukpage.com/terribleNew")
     * and  RH12345 will be transalated as
     * <a href="https://bugzilla.redhat.com/show_bug.cgi?id=123456">123456<a> or
     * similar, the url will be inclueded as is. Both added to proper tests or suites
     *
     * @return Strng[2]{nameToBeShown, hrefValue}
     */
    public static String[] createBug(String string) {
        String[] r = {"ex", string};
        String[] prefixes = {
            "S",
            "PR",
            "RH",
            "D",
            "G",
            "CA",
            "LP",};
        String[] urls = {
            "http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=",
            "http://icedtea.classpath.org/bugzilla/show_bug.cgi?id=",
            "https://bugzilla.redhat.com/show_bug.cgi?id=",
            "http://bugs.debian.org/cgi-bin/bugreport.cgi?bug=",
            "http://bugs.gentoo.org/show_bug.cgi?id=",
            "http://server.complang.tuwien.ac.at/cgi-bin/bugzilla/show_bug.cgi?id",
            "https://bugs.launchpad.net/bugs/",};

        for (int i = 0; i < urls.length; i++) {
            if (string.startsWith(prefixes[i])) {
                r[0] = string;
                r[1] = urls[i] + string.substring(prefixes[i].length());
                return r;
            }

        }

        String distro = "http://mail.openjdk.java.net/pipermail/distro-pkg-dev/";
        String openjdk = "http://mail.openjdk.java.net/pipermail/";
        if (string.startsWith(distro)) {
            r[0] = "distro-pkg";
            return r;
        }
        if (string.startsWith(openjdk)) {
            r[0] = "openjdk";
            return r;
        }
        return r;

    }

    public static void main(String[] args) {
        String[] q = createBug("PR608");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("S4854");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("RH649423");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("D464");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("G6554");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("CA1654");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("LP5445");
        System.out.println(q[0] + " : " + q[1]);

        q = createBug("http://mail.openjdk.java.net/pipermail/distro-pkg-dev/2011-November/016178.html");
        System.out.println(q[0] + " : " + q[1]);
        q = createBug("http://mail.openjdk.java.net/pipermail/awt-dev/2012-March/002324.html");
        System.out.println(q[0] + " : " + q[1]);

        q = createBug("http://lists.fedoraproject.org/pipermail/chinese/2012-January/008868.html");
        System.out.println(q[0] + " : " + q[1]);
    }
}
