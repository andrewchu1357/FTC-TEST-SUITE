package com.acmerobotics.dashboard.telemetry;

import org.firstinspires.ftc.robotcore.external.Func;
import org.firstinspires.ftc.robotcore.external.Telemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sends telemetry to several Telemetry objects at once (usually the Driver Station and FTC Dashboard). */
public class MultipleTelemetry implements Telemetry {
    private final List<Telemetry> telemetries;

    public MultipleTelemetry(Telemetry... telemetries) {
        this.telemetries = new ArrayList<Telemetry>(Arrays.asList(telemetries));
    }

    public void addTelemetry(Telemetry telemetry) { telemetries.add(telemetry); }

    @Override
    public Item addData(String caption, String format, Object... args) {
        Item first = null;
        for (Telemetry t : telemetries) { Item i = t.addData(caption, format, args); if (first == null) first = i; }
        return first;
    }

    @Override
    public Item addData(String caption, Object value) {
        Item first = null;
        for (Telemetry t : telemetries) { Item i = t.addData(caption, value); if (first == null) first = i; }
        return first;
    }

    @Override
    public <T> Item addData(String caption, Func<T> valueProducer) {
        Item first = null;
        for (Telemetry t : telemetries) { Item i = t.addData(caption, valueProducer); if (first == null) first = i; }
        return first;
    }

    @Override
    public <T> Item addData(String caption, String format, Func<T> valueProducer) {
        Item first = null;
        for (Telemetry t : telemetries) { Item i = t.addData(caption, format, valueProducer); if (first == null) first = i; }
        return first;
    }

    @Override
    public boolean removeItem(Item item) {
        boolean any = false;
        for (Telemetry t : telemetries) any |= t.removeItem(item);
        return any;
    }

    @Override public void clear() { for (Telemetry t : telemetries) t.clear(); }
    @Override public void clearAll() { for (Telemetry t : telemetries) t.clearAll(); }

    @Override
    public Object addAction(Runnable action) {
        return telemetries.isEmpty() ? null : telemetries.get(0).addAction(action);
    }

    @Override
    public boolean removeAction(Object token) {
        return !telemetries.isEmpty() && telemetries.get(0).removeAction(token);
    }

    @Override public void speak(String text) { for (Telemetry t : telemetries) t.speak(text); }
    @Override public void speak(String text, String languageCode, String countryCode) { for (Telemetry t : telemetries) t.speak(text, languageCode, countryCode); }

    @Override
    public boolean update() {
        boolean any = false;
        for (Telemetry t : telemetries) any |= t.update();
        return any;
    }

    @Override
    public Line addLine() {
        Line first = null;
        for (Telemetry t : telemetries) { Line l = t.addLine(); if (first == null) first = l; }
        return first;
    }

    @Override
    public Line addLine(String lineCaption) {
        Line first = null;
        for (Telemetry t : telemetries) { Line l = t.addLine(lineCaption); if (first == null) first = l; }
        return first;
    }

    @Override
    public boolean removeLine(Line line) {
        boolean any = false;
        for (Telemetry t : telemetries) any |= t.removeLine(line);
        return any;
    }

    @Override public boolean isAutoClear() { return telemetries.isEmpty() || telemetries.get(0).isAutoClear(); }
    @Override public void setAutoClear(boolean autoClear) { for (Telemetry t : telemetries) t.setAutoClear(autoClear); }
    @Override public int getMsTransmissionInterval() { return telemetries.isEmpty() ? 250 : telemetries.get(0).getMsTransmissionInterval(); }
    @Override public void setMsTransmissionInterval(int ms) { for (Telemetry t : telemetries) t.setMsTransmissionInterval(ms); }
    @Override public String getItemSeparator() { return telemetries.isEmpty() ? " | " : telemetries.get(0).getItemSeparator(); }
    @Override public void setItemSeparator(String s) { for (Telemetry t : telemetries) t.setItemSeparator(s); }
    @Override public String getCaptionValueSeparator() { return telemetries.isEmpty() ? " : " : telemetries.get(0).getCaptionValueSeparator(); }
    @Override public void setCaptionValueSeparator(String s) { for (Telemetry t : telemetries) t.setCaptionValueSeparator(s); }
    @Override public void setDisplayFormat(DisplayFormat f) { for (Telemetry t : telemetries) t.setDisplayFormat(f); }

    @Override
    public Log log() {
        return telemetries.isEmpty() ? null : telemetries.get(0).log();
    }
}
