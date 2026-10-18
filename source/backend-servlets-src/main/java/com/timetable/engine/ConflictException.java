package com.timetable.engine;

public class ConflictException extends Exception {
    public final String type;
    public String suggestion;
    public Integer suggestionSlotId;

    public ConflictException(String type, String message) {
        super(message);
        this.type = type;
    }
}
