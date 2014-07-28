package com.obera.jasdb.web.model;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * @author renarj
 */
public class WebInstance {
    @NotNull
    @Size(min = 1)
    private String name;

    @NotNull
    @Size(min = 1)
    private String path;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }
}
