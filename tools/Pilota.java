import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;

/**
 * Pilota la UI dentro un server X separato. `java.awt.Robot` inietta via XTEST nel server X
 * indicato dal DISPLAY del proprio JVM: avviato con DISPLAY=:2 gli eventi finiscono in Xephyr e
 * la sessione reale non se ne accorge — puntatore, fuoco e appunti di :0 restano intatti.
 *
 * Legge un comando per riga da stdin:
 *   move X Y | click X Y | dblclick X Y | key NOME[+NOME] | type TESTO
 *   wheel N (negativo = su) | wait MS | shot FILE
 *
 * `type` non batte i tasti: mette il testo negli appunti del display separato e incolla con
 * Ctrl+V. Mappare gli accenti sui keycode di Robot sarebbe fragile, gli appunti li reggono senza
 * mappature. Ne segue un vincolo: l'incolla deve avvenire nella stessa esecuzione che imposta gli
 * appunti, perché in X11 la selezione vive nel processo che la possiede e dentro Xephyr non c'è
 * nessun clipboard manager a raccoglierla quando questo JVM esce.
 *
 * Le coordinate si ricavano dagli screenshot (`shot`): non c'è un albero di accessibilità da
 * interrogare, quindi ogni click va guardato prima e verificato dopo.
 */
public class Pilota {

    static Robot robot;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(40);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String riga;
        while ((riga = in.readLine()) != null) {
            riga = riga.trim();
            if (riga.isEmpty() || riga.startsWith("#")) continue;
            int spazio = riga.indexOf(' ');
            String comando = spazio < 0 ? riga : riga.substring(0, spazio);
            String resto = spazio < 0 ? "" : riga.substring(spazio + 1).trim();
            esegui(comando, resto);
            System.out.println("ok: " + riga);
        }
    }

    static void esegui(String comando, String resto) throws Exception {
        String[] p = resto.isEmpty() ? new String[0] : resto.split("\\s+");
        switch (comando) {
            case "move" -> robot.mouseMove(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            case "click" -> click(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 1);
            case "dblclick" -> click(Integer.parseInt(p[0]), Integer.parseInt(p[1]), 2);
            case "key" -> tasto(resto);
            case "type" -> scrivi(resto);
            case "wait" -> Thread.sleep(Long.parseLong(p[0]));
            case "wheel" -> { robot.mouseWheel(Integer.parseInt(p[0])); robot.delay(300); }
            case "shot" -> scatta(resto);
            default -> throw new IllegalArgumentException("comando sconosciuto: " + comando);
        }
    }

    static void click(int x, int y, int volte) {
        robot.mouseMove(x, y);
        robot.delay(120);
        for (int i = 0; i < volte; i++) {
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        robot.delay(250);
    }

    /** "ENTER", "TAB", "CTRL+A": i modificatori si premono in ordine e si rilasciano al contrario. */
    static void tasto(String descrizione) throws Exception {
        String[] parti = descrizione.toUpperCase().split("\\+");
        int[] codici = new int[parti.length];
        for (int i = 0; i < parti.length; i++) {
            codici[i] = KeyEvent.class.getField("VK_" + parti[i]).getInt(null);
        }
        for (int codice : codici) robot.keyPress(codice);
        for (int i = codici.length - 1; i >= 0; i--) robot.keyRelease(codici[i]);
        robot.delay(200);
    }

    static void scrivi(String testo) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
            .setContents(new StringSelection(testo), null);
        robot.delay(150);
        robot.keyPress(KeyEvent.VK_CONTROL);
        robot.keyPress(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_V);
        robot.keyRelease(KeyEvent.VK_CONTROL);
        robot.delay(250);
    }

    static void scatta(String file) throws Exception {
        Dimension schermo = Toolkit.getDefaultToolkit().getScreenSize();
        BufferedImage immagine = robot.createScreenCapture(new Rectangle(schermo));
        ImageIO.write(immagine, "png", new File(file));
    }
}
