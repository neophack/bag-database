// *****************************************************************************
//
// Copyright (c) 2020, Southwest Research Institute® (SwRI®)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//     * Redistributions of source code must retain the above copyright
//       notice, this list of conditions and the following disclaimer.
//     * Redistributions in binary form must reproduce the above copyright
//       notice, this list of conditions and the following disclaimer in the
//       documentation and/or other materials provided with the distribution.
//     * Neither the name of Southwest Research Institute® (SwRI®) nor the
//       names of its contributors may be used to endorse or promote products
//       derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL Southwest Research Institute® BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
// SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
// OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
// DAMAGE.
//
// *****************************************************************************

Ext.define('BagDatabase.views.StatusText', {
    extend: 'Ext.toolbar.TextItem',
    alias: 'widget.statusText',
    data: {
        source: 'UI',
        status: {
            state: 'WORKING',
            message: 'Test'
        }
    },
    errorButton: null,
    initComponent: function() {
        this.tpl = new Ext.XTemplate(
            '<tpl if="status.state == \'IDLE\'">' +
                '<div class="status-text-icon tick-icon"/>' +
            '<tpl elseif="status.state == \'WORKING\'"">' +
                '<div class="status-text-icon loading-icon"/>' +
            '<tpl elseif="status.state == \'ERROR\'"">' +
                '<div class="status-text-icon cross-icon"/>' +
            '</tpl> ' +
            '<tpl if="source">{source}: </tpl>{status.message}');
        this.callParent(arguments);
    },
    connectWebSocket: function(errorButton) {
        if (errorButton) {
            this.errorButton = errorButton;
        }
        var me, viewport;
        me = this;
        viewport = me.up('viewport');
        // Manually get the latest status before we connect.
        Ext.Ajax.request({
            url: 'status/latest',
            callback: function(options, success, response) {
                var status = null;
                if (response && response.responseText) {
                    status = Ext.JSON.decode(response.responseText);
                }
                me.updateStatus(status);
            }
        });

        if (viewport) {
            viewport.subscribeToTopic('/topic/status',
                function(status){
                    me.updateStatus(Ext.JSON.decode(status.body));
                });
        }
        else {
            console.error('Unable to find viewport; will not be able to receive status updates.');
        }
    },
    updateStatus: function(status) {
        if (!status) {
            this.setData({status:{message:'Error getting status!'}});
        }
        else {
            this.setData(status);
            if (this.errorButton) {
                this.errorButton.setText(status.errorCount + ' errors');
                this.errorButton.setDisabled(status.errorCount == 0);
            }
        }
    }
});