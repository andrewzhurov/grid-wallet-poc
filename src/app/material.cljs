(ns app.material
  (:require [daiquiri.interpreter] ;; we need to load it at least once in project, as util macroses rely on it to be present at runtime
            ["@mui/material/SpeedDial$default" :as speed-dial]
            ["@mui/material/SpeedDialIcon$default" :as speed-dial-icon]
            ["@mui/material/SpeedDialAction$default" :as speed-dial-action]

            ["@mui/material/Icon$default" :as icon]
            ["@mui/icons-material/Fingerprint$default" :as icon-fingerprint]
            ["@mui/icons-material/Send$default" :as icon-send]


            ["@mui/material/Dialog$default" :as dialog]
            ["@mui/material/DialogTitle$default" :as dialog-title]
            ["@mui/material/DialogContent$default" :as dialog-content]
            ["@mui/material/DialogContentText$default" :as dialog-content-text]
            ["@mui/material/DialogActions$default" :as dialog-actions]

            ["@mui/material/Box$default" :as box]
            ["@mui/material/Container$default" :as container]
            ["@mui/material/Button$default" :as button]
            ["@mui/material/Fab$default" :as fab]
            ["@mui/material/TextField$default" :as text-field]
            ["@mui/material/Checkbox$default" :as checkbox]
            ["@mui/material/Avatar$avatar" :as avatar]

            ["@mui/material/Zoom$default" :as zoom]
            ["@mui/material/Fade$default" :as fade]

            ["@mui/base$ClickAwayListener" :as click-away-listener]
            ["@mui/base/TextareaAutosize$TextareaAutosize" :as textarea-autosize]

            ["@mui/material/List$default" :as mui-list]
            ["@mui/material/ListItem$default" :as list-item]
            ["@mui/material/ListItemButton$default" :as list-item-button]
            ["@mui/material/ListItemText$default" :as list-item-text]
            ["@mui/material/ListItemAvatar" :as list-item-avatar]

            ["@mui/lab/TabContext$default" :as tab-context]
            ["@mui/lab/TabList$default" :as tab-list]
            ["@mui/material/Tab$default" :as tab]
            ["@mui/lab/TabPanel$default" :as tab-panel]
            ))
