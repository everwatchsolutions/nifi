/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/* global nf, d3 */

nf.ContextMenu = (function () {

    /**
     * Returns whether the current group is not the root group.
     *
     * @param {selection} selection         The selection of currently selected components
     */
    var isNotRootGroup = function (selection) {
        return nf.Canvas.getParentGroupId() !== null && selection.empty();
    };

    /**
     * Determines whether the component in the specified selection is configurable.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var isConfigurable = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }

        var isConfigurableComponent = nf.CanvasUtils.isLabel(selection) || nf.CanvasUtils.isProcessGroup(selection);
        if (!isConfigurableComponent) {
            if (nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isInputPort(selection) || nf.CanvasUtils.isOutputPort(selection) || nf.CanvasUtils.isRemoteProcessGroup(selection) || nf.CanvasUtils.isConnection(selection)) {
                isConfigurableComponent = nf.CanvasUtils.supportsModification(selection);
            }
        }

        return isConfigurableComponent;
    };

    /**
     * Determines whether the component in the specified selection has configuration details.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var hasDetails = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }
        if (nf.CanvasUtils.canRead(selection) === false) {
            return false;
        }

        if (nf.CanvasUtils.canModify(selection)) {
            if (nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isInputPort(selection) || nf.CanvasUtils.isOutputPort(selection) || nf.CanvasUtils.isRemoteProcessGroup(selection) || nf.CanvasUtils.isConnection(selection)) {
                return !nf.CanvasUtils.supportsModification(selection);
            }
        } else {
            return nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isConnection(selection) || nf.CanvasUtils.isProcessGroup(selection) || nf.CanvasUtils.isInputPort(selection) || nf.CanvasUtils.isOutputPort(selection) || nf.CanvasUtils.isRemoteProcessGroup(selection);
        }

        return false;
    };

    /**
     * Determines whether the components in the specified selection are deletable.
     * 
     * @param {selection} selection         The selection of currently selected components 
     */
    var isDeletable = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.isDeletable(selection);
    };

    /**
     * Determines whether the components in the specified selection are runnable.
     * 
     * @param {selection} selection         The selection of currently selected components 
     */
    var isRunnable = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.areRunnable(selection);
    };

    /**
     * Determines whether the components in the specified selection are stoppable.
     * 
     * @param {selection} selection         The selection of currently selected components 
     */
    var isStoppable = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.areStoppable(selection);
    };

    /**
     * Determines whether the components in the specified selection support stats.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var supportsStats = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isProcessGroup(selection) || nf.CanvasUtils.isRemoteProcessGroup(selection) || nf.CanvasUtils.isConnection(selection);
    };

    /**
     * Determines whether the components in the specified selection has usage documentation.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var hasUsage = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return nf.CanvasUtils.isProcessor(selection);
    };

    /**
     * Determines whether there is one component selected.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var isNotConnection = function (selection) {
        return selection.size() === 1 && !nf.CanvasUtils.isConnection(selection);
    };

    /**
     * Determines whether the components in the specified selection are copyable.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var isCopyable = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.isCopyable(selection);
    };

    /**
     * Determines whether the components in the specified selection are pastable.
     * 
     * @param {selection} selection         The selection of currently selected components
     */
    var isPastable = function (selection) {
        return nf.Common.isDFM() && nf.CanvasUtils.isPastable();
    };

    /**
     * Determines whether the specified selection is empty.
     * 
     * @param {selection} selection         The seleciton
     */
    var emptySelection = function (selection) {
        return selection.empty();
    };

    /**
     * Determines whether the componets in the specified selection support being moved to the front.
     * 
     * @param {selection} selection         The selection
     */
    var canMoveToFront = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }

        return nf.CanvasUtils.isConnection(selection);
    };

    /**
     * Determines whether the components in the specified selection are colorable.
     * 
     * @param {selection} selection          The selection
     */
    var isColorable = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.isColorable(selection);
    };

    /**
     * Determines whether the component in the specified selection is a connection.
     * 
     * @param {selection} selection         The selection
     */
    var isConnection = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return nf.CanvasUtils.isConnection(selection);
    };

    /**
     * Determines whether the component in the specified selection could possibly have downstream components.
     * 
     * @param {selection} selection         The selection
     */
    var hasDownstream = function (selection) {
        // TODO
        // // ensure the correct number of components are selected
        // if (selection.size() !== 1) {
        //     return false;
        // }
        //
        // return nf.CanvasUtils.isFunnel(selection) || nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isProcessGroup(selection) ||
        //         nf.CanvasUtils.isRemoteProcessGroup(selection) || nf.CanvasUtils.isInputPort(selection) ||
        //         (nf.CanvasUtils.isOutputPort(selection) && nf.Canvas.getParentGroupId() !== null);
        return false;
    };

    /**
     * Determines whether the component in the specified selection could possibly have upstream components.
     * 
     * @param {selection} selection         The selection
     */
    var hasUpstream = function (selection) {
        // TODO
        // // ensure the correct number of components are selected
        // if (selection.size() !== 1) {
        //     return false;
        // }
        //
        // return nf.CanvasUtils.isFunnel(selection) || nf.CanvasUtils.isProcessor(selection) || nf.CanvasUtils.isProcessGroup(selection) ||
        //         nf.CanvasUtils.isRemoteProcessGroup(selection) || nf.CanvasUtils.isOutputPort(selection) ||
        //         (nf.CanvasUtils.isInputPort(selection) && nf.Canvas.getParentGroupId() !== null);
        return false;
    };

    /**
     * Determines whether the current selection is a processor.
     *
     * @param {selection} selection
     */
    var isStatefulProcessor = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }

        if (nf.CanvasUtils.isProcessor(selection)) {
            var processorData = selection.datum();
            return processorData.component.persistsState === true;
        } else {
            return false;
        }
    };

    /**
     * Determines whether the current selection is a process group.
     * 
     * @param {selection} selection
     */
    var isProcessGroup = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return nf.CanvasUtils.isProcessGroup(selection);
    };

    /**
     * Determines whether the current selection could have provenance.
     *
     * @param {selection} selection
     */
    var canAccessProvenance = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return !nf.CanvasUtils.isLabel(selection) && !nf.CanvasUtils.isConnection(selection) && !nf.CanvasUtils.isProcessGroup(selection)
            && !nf.CanvasUtils.isRemoteProcessGroup(selection) && nf.Common.canAccessProvenance();
    };

    /**
     * Determines whether the current selection is a remote process group.
     * 
     * @param {selection} selection         
     */
    var isRemoteProcessGroup = function (selection) {
        // ensure the correct number of components are selected
        if (selection.size() !== 1) {
            return false;
        }

        return nf.CanvasUtils.isRemoteProcessGroup(selection);
    };

    /**
     * Determines if the components in the specified selection support starting transmission.
     * 
     * @param {selection} selection
     */
    var canStartTransmission = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.canAllStartTransmitting(selection);
    };

    /**
     * Determines if the components in the specified selection support stopping transmission.
     * 
     * @param {selection} selection
     */
    var canStopTransmission = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return nf.CanvasUtils.canAllStopTransmitting(selection);
    };
    
    /**
     * Only DFMs can empty a queue.
     * 
     * @param {selection} selection
     */
    var canEmptyQueue = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return isConnection(selection);
    };

    /**
     * Only DFMs can list a queue.
     *
     * @param {selection} selection
     */
    var canListQueue = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }
        
        return isConnection(selection);
    };
    
    /**
     * Determines if the components in the specified selection can be moved into a parent group.
     * 
     * @param {type} selection
     */
    var canMoveToParent = function (selection) {
        if (nf.CanvasUtils.canModify(selection) === false) {
            return false;
        }

        // TODO - also check can modify in parent
        
        return !selection.empty() && nf.CanvasUtils.isDisconnected(selection) && nf.Canvas.getParentGroupId() !== null;
    };

    /**
     * Adds a menu item to the context menu.
     * 
     * {
     *      click: refresh (function),
     *      text: 'Start' (string),
     *      img: 'images/iconRun.png'
     * }
     * 
     * @param {jQuery} contextMenu The context menu
     * @param {object} item The menu item configuration
     */
    var addMenuItem = function (contextMenu, item) {
        if (typeof item.click === 'function') {
            var menuItem = $('<div class="context-menu-item"></div>').on('click', item['click']).on('contextmenu', function (evt) {
                // invoke the desired action
                item['click'](evt);

                // stop propagation and prevent default
                evt.preventDefault();
                evt.stopPropagation();
            }).on('mouseenter', function () {
                $(this).addClass('hover');
            }).on('mouseleave', function () {
                $(this).removeClass('hover');
            }).appendTo(contextMenu);

            // create the img and conditionally add the style
            var img = $('<div class="context-menu-item-img"></div>').css('background-image', 'url(' + item['img'] + ')').appendTo(menuItem);
            if (nf.Common.isDefinedAndNotNull(item['imgStyle'])) {
                img.addClass(item['imgStyle']);
            }
            
            $('<div class="context-menu-item-text"></div>').text(item['text']).appendTo(menuItem);
            $('<div class="clear"></div>').appendTo(menuItem);
        }
    };

    /**
     * Positions and shows the context menu.
     * 
     * @param {jQuery} contextMenu  The context menu
     * @param {object} options      The context menu configuration
     */
    var positionAndShow = function (contextMenu, options) {
        contextMenu.css({
            'left': options.x + 'px',
            'top': options.y + 'px'
        }).show();
    };

    /**
     * Executes the specified action with the optional selection.
     * 
     * @param {string} action
     * @param {selection} selection
     * @param {mouse event} evt
     */
    var executeAction = function (action, selection, evt) {
        // execute the action
        nf.Actions[action](selection, evt);

        // close the context menu
        nf.ContextMenu.hide();
    };

    // defines the available actions and the conditions which they apply
    var actions = [
        {condition: emptySelection, menuItem: {img: 'images/iconRefresh.png', text: 'Refresh', action: 'reload'}},
        {condition: isNotRootGroup, menuItem: {img: 'images/iconGoTo.png', text: 'Leave group', action: 'leaveGroup'}},
        {condition: isConfigurable, menuItem: {img: 'images/iconConfigure.png', text: 'Configure', action: 'showConfiguration'}},
        {condition: hasDetails, menuItem: {img: 'images/iconConfigure.png', text: 'View configuration', action: 'showDetails'}},
        {condition: isProcessGroup, menuItem: {img: 'images/iconGoTo.png', text: 'Enter group', action: 'enterGroup'}},
        {condition: isRunnable, menuItem: {img: 'images/iconRun.png', text: 'Start', action: 'start'}},
        {condition: isStoppable, menuItem: {img: 'images/iconStop.png', text: 'Stop', action: 'stop'}},
        {condition: isRemoteProcessGroup, menuItem: {img: 'images/iconRemotePorts.png', text: 'Remote ports', action: 'remotePorts'}},
        {condition: canStartTransmission, menuItem: {img: 'images/iconTransmissionActive.png', text: 'Enable transmission', action: 'enableTransmission'}},
        {condition: canStopTransmission, menuItem: {img: 'images/iconTransmissionInactive.png', text: 'Disable transmission', action: 'disableTransmission'}},
        {condition: supportsStats, menuItem: {img: 'images/iconChart.png', text: 'Stats', action: 'showStats'}},
        {condition: canAccessProvenance, menuItem: {img: 'images/iconProvenance.png', imgStyle: 'context-menu-provenance', text: 'Data provenance', action: 'openProvenance'}},
        {condition: isStatefulProcessor, menuItem: {img: 'images/iconViewState.png', text: 'View state', action: 'viewState'}},
        {condition: canMoveToFront, menuItem: {img: 'images/iconToFront.png', text: 'Bring to front', action: 'toFront'}},
        {condition: isConnection, menuItem: {img: 'images/iconGoTo.png', text: 'Go to source', action: 'showSource'}},
        {condition: isConnection, menuItem: {img: 'images/iconGoTo.png', text: 'Go to destination', action: 'showDestination'}},
        {condition: hasUpstream, menuItem: {img: 'images/iconSmallRelationship.png', text: 'Upstream connections', action: 'showUpstream'}},
        {condition: hasDownstream, menuItem: {img: 'images/iconSmallRelationship.png', text: 'Downstream connections', action: 'showDownstream'}},
        {condition: hasUsage, menuItem: {img: 'images/iconUsage.png', text: 'Usage', action: 'showUsage'}},
        {condition: isRemoteProcessGroup, menuItem: {img: 'images/iconRefresh.png', text: 'Refresh', action: 'refreshRemoteFlow'}},
        {condition: isRemoteProcessGroup, menuItem: {img: 'images/iconGoTo.png', text: 'Go to', action: 'openUri'}},
        {condition: isColorable, menuItem: {img: 'images/iconColor.png', text: 'Change color', action: 'fillColor'}},
        {condition: isNotConnection, menuItem: {img: 'images/iconCenterView.png', text: 'Center in view', action: 'center'}},
        {condition: isCopyable, menuItem: {img: 'images/iconCopy.png', text: 'Copy', action: 'copy'}},
        {condition: isPastable, menuItem: {img: 'images/iconPaste.png', text: 'Paste', action: 'paste'}},
        {condition: canMoveToParent, menuItem: {img: 'images/iconMoveToParent.png', text: 'Move to parent group', action: 'moveIntoParent'}},
        {condition: canListQueue, menuItem: {img: 'images/iconListQueue.png', text: 'List queue', action: 'listQueue'}},
        {condition: canEmptyQueue, menuItem: {img: 'images/iconEmptyQueue.png', text: 'Empty queue', action: 'emptyQueue'}},
        {condition: isDeletable, menuItem: {img: 'images/iconDelete.png', text: 'Delete', action: 'delete'}}
    ];

    return {
        init: function () {
            $('#context-menu').on('contextmenu', function(evt) {
                // stop propagation and prevent default
                evt.preventDefault();
                evt.stopPropagation();
            });
        },
        
        /**
         * Shows the context menu. 
         */
        show: function () {
            var contextMenu = $('#context-menu').empty();
            var canvasBody = $('#canvas-body').get(0);
            var bannerFooter = $('#banner-footer').get(0);
            var breadCrumb = $('#breadcrumbs').get(0);

            // get the current selection
            var selection = nf.CanvasUtils.getSelection();

            // consider each component action for the current selection
            $.each(actions, function (_, action) {
                // determine if this action is application for this selection
                if (action.condition(selection)) {
                    var menuItem = action.menuItem;

                    addMenuItem(contextMenu, {
                        img: menuItem.img,
                        imgStyle: menuItem.imgStyle, 
                        text: menuItem.text,
                        click: function (evt) {
                            executeAction(menuItem.action, selection, evt);
                        }
                    });
                }
            });

            // get the location for the context menu
            var position = d3.mouse(canvasBody);

            // nifi 1864 make sure the context menu is not hidden by the browser boundaries
            if (position[0] + contextMenu.width() > canvasBody.clientWidth) {
                position[0] = canvasBody.clientWidth - contextMenu.width() - 2;
            }
            if (position[1] + contextMenu.height() > (canvasBody.clientHeight - breadCrumb.clientHeight - bannerFooter.clientHeight)) {
                position[1] = canvasBody.clientHeight - breadCrumb.clientHeight - bannerFooter.clientHeight - contextMenu.height() - 3;
            }

            // show the context menu
            positionAndShow(contextMenu, {
                'x': position[0],
                'y': position[1]
            });

            // inform Angular app incase we've click on the canvas
            nf.ng.Bridge.digest();
        },
        
        /**
         * Hides the context menu.
         */
        hide: function () {
            $('#context-menu').hide();
        },
        
        /**
         * Activates the context menu for the components in the specified selection.
         * 
         * @param {selection} components    The components to enable the context menu for
         */
        activate: function (components) {
            components.on('contextmenu.selection', function () {
                // get the clicked component to update selection
                nf.ContextMenu.show();

                // stop propagation and prevent default
                d3.event.preventDefault();
                d3.event.stopPropagation();
            });
        }
    };
}());