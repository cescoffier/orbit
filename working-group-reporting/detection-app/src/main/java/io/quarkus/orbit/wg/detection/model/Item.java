package io.quarkus.orbit.wg.detection.model;

public record Item(
        String id,
        String type,
        Content content,
        StatusFieldValue fieldValueByName) {


    public String status() {
        if (fieldValueByName!= null) {
            return fieldValueByName.status();
        } else {
            return "todo";
        }
    }

    public StatusFieldValue statusDetails() {
        return fieldValueByName;
    }

    public String url() {
        return content.url();
    }

    public String title() {
        return content.title();
    }

}