---
title: Generation of PDF files
identifier: intranda_step_createfullpdf
published: true
description: This is the technical documentation for the Goobi plugin for automatically creating PDF files out of images.
---
## Introduction
This documentation describes how to install and configure this plugin to create PDF files out of images.


## Installation 
To use the plugin, it must be copied to the following location:

```bash
/opt/digiverso/goobi/plugins/step/plugin_intranda_step_createfullpdf-base.jar
```

The configuration of the plugin is expected under the following path:

```bash
/opt/digiverso/goobi/config/plugin_intranda_step_createfullpdf.xml
```

## Overview and functionality
Once the plugin has been installed correctly, it can be configured in the user interface for use within the workflow for the desired work step. To do this, the value `intranda_step_createfullpdf` must be selected as the plugin and the filling should be set as automatic.

![Selection of the plugin within the workflow configuration](screen1_en.png)


## Configuration
An example configuration could look like this:

```xml
<config_plugin>
    <!-- order of configuration is: 
         1.) project name and step name matches 
         2.) step name matches and project is * 
         3.) project name matches and step name is * 
         4.) project name and step name are * 
    -->
    <config>
        <!-- which projects to use for (can be more then one, otherwise use *) -->
        <project>*</project>

        <!-- which stepss to use for (can be more then one, otherwise use *) -->
        <step>*</step>

         <!-- Choose the source images folder that shall be used for PDF generation. Possible values are 'media' and 'master' -->
        <imagesFolder>media</imagesFolder>

        <!-- If set to true, then PDF files for every single image are generated before the full PDF file. -->
        <!-- If set to false, then PDF files for every single image are generated after the full PDF file. -->
        <pagePdf enabled="true" /> 

        <!-- If enabled, a PDF file for the entire record is generated. This file includes the table of contents from the METS file if exists. -->
        <!-- The attribute pdfConfigVariant sets up which config variant in contentServerConfig.xml should be used. If not set, then use default. -->
        <fullPdf enabled="true" pdfConfigVariant="pdfa"/>
        
        <!-- If set, then the path will be used to export the results. Otherwise the default settings will be used. -->
        <!-- Here is an ABSOLUTE path expected. -->
        <exportPath>/tmp/export</exportPath>
    </config>
    
    <config>
        <project>testocr</project>
        <step>testpdf</step>
        <imagesFolder>master</imagesFolder>
        <pagePdf enabled="true" />
        <fullPdf enabled="false" />
    </config>
</config_plugin>
```


The parameters within this configuration file have the following meanings: ​

| Value | Description |
| :--- | :--- |
| `imageFolder` | This parameter expects the name of the image folder. Possible values are `media` and `master`, anything else will be regarded as `media`. |
| `pagePdf` | This parameter determines the order of PDF files' generation. If set `true`, then single files are generated before the full PDF file; if set `false` then the other way around. |
| `fullPdf` | This parameter has two attributes. The first one `enabled` is mandatory and determines whether or not to generate a full PDF file. The second one `pdfConfigVariant` is optional and controls which config variant shall be used, and if not set, then `default` will be used. |
| `exportPath` | This optional parameter can be used to set up another path to export the PDF files. An absolute path is expected if in use. If not set, then the default settings will be used. |
